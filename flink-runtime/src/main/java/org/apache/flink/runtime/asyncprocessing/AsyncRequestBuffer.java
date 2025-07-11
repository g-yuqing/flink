/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.asyncprocessing;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.util.concurrent.ExecutorThreadFactory;

import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A buffer to hold async requests to execute async requests in batch, which can only be manipulated
 * within task thread.
 *
 * @param <K> the type of the key
 */
@NotThreadSafe
public class AsyncRequestBuffer<K> implements Closeable {

    /** All asyncRequestBuffer in the same task manager share one ScheduledExecutorService. */
    private static final ScheduledThreadPoolExecutor DELAYER =
            new ScheduledThreadPoolExecutor(
                    1, new ExecutorThreadFactory("asyncRequestBuffer-timeout-scheduler"));

    static {
        DELAYER.setRemoveOnCancelPolicy(true);
        DELAYER.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        DELAYER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /**
     * The async requests in this buffer could be executed when the buffer is full or configured
     * batch size is reached. All operations on this buffer must be invoked in task thread.
     */
    final LinkedList<AsyncRequest<K>> activeQueue;

    /**
     * The requests in that should wait until all preceding records with identical key finishing its
     * execution. After which the queueing requests will move into the active buffer. All operations
     * on this buffer must be invoked in task thread.
     */
    final Map<K, LinkedList<AsyncRequest<K>>> blockingQueue;

    /** The number of async requests in blocking queue. */
    int blockingQueueSize;

    /** The timeout of {@link #activeQueue} triggering in milliseconds. */
    final long bufferTimeout;

    /** The interval of periodic buffer timeout check. */
    final long bufferTimeoutCheckInterval;

    /** The handler to trigger when timeout. */
    final Consumer<Long> timeoutHandler;

    /** The executor service that schedules and calls the triggers of this task. */
    final ScheduledExecutorService scheduledExecutor;

    /**
     * The current scheduled future, when the next scheduling occurs, the previous one that has not
     * yet been executed will be canceled.
     */
    ScheduledFuture<?> currentScheduledFuture;

    /**
     * The current scheduled trigger sequence number, a timeout trigger is scheduled only if {@code
     * scheduledSeq} is less than {@code currentSeq}.
     */
    volatile Tuple2<Long, Long> seqAndTimeout = null;

    /**
     * The current trigger sequence number, used to distinguish different triggers. Every time a
     * trigger occurs, {@code currentSeq} increases by 1.
     */
    final AtomicLong currentSeq;

    public AsyncRequestBuffer(
            long bufferTimeout, long bufferTimeoutCheckInterval, Consumer<Long> timeoutHandler) {
        this.activeQueue = new LinkedList<>();
        this.blockingQueue = new HashMap<>();
        this.blockingQueueSize = 0;
        this.bufferTimeout = bufferTimeout;
        this.bufferTimeoutCheckInterval = bufferTimeoutCheckInterval;
        this.timeoutHandler = timeoutHandler;
        this.currentSeq = new AtomicLong(0);
        if (bufferTimeout > 0) {
            this.scheduledExecutor = DELAYER;
            initPeriodicTimeoutCheck();
        } else {
            this.scheduledExecutor = null;
        }
    }

    private void initPeriodicTimeoutCheck() {
        currentScheduledFuture =
                scheduledExecutor.scheduleAtFixedRate(
                        () -> {
                            final Tuple2<Long, Long> theSeqAndTimeout = seqAndTimeout;
                            if (theSeqAndTimeout != null
                                    && theSeqAndTimeout.f0 == currentSeq.get()
                                    && theSeqAndTimeout.f1 <= System.currentTimeMillis()) {
                                timeoutHandler.accept(theSeqAndTimeout.f0);
                            }
                        },
                        bufferTimeout,
                        bufferTimeoutCheckInterval,
                        TimeUnit.MILLISECONDS);
    }

    void advanceSeq() {
        seqAndTimeout = null;
        currentSeq.incrementAndGet();
    }

    boolean checkCurrentSeq(long seq) {
        return currentSeq.get() == seq;
    }

    void enqueueToActive(AsyncRequest<K> request) {
        activeQueue.add(request);
        if (bufferTimeout > 0 && seqAndTimeout == null) {
            seqAndTimeout = Tuple2.of(currentSeq.get(), System.currentTimeMillis() + bufferTimeout);
        }
    }

    void enqueueToBlocking(AsyncRequest<K> request) {
        LinkedList<AsyncRequest<K>> currentList =
                blockingQueue.computeIfAbsent(
                        request.getRecordContext().getKey(), k -> new LinkedList<>());
        if (currentList.isEmpty()) {
            currentList.addLast(request);
        } else {
            int priority = request.getRecordContext().getPriority();
            if (priority == RecordContext.PRIORITY_MIN) {
                currentList.addLast(request);
            } else {
                // Insert 'request' before the first element whose priority smaller than 'priority'
                // This is a rare case with greater overhead.
                boolean inserted = false;
                ListIterator<AsyncRequest<K>> iterator = currentList.listIterator(0);
                while (!inserted && iterator.hasNext()) {
                    AsyncRequest<K> iterRequest = iterator.next();
                    if (iterRequest.getRecordContext().getPriority() < priority) {
                        iterator.previous(); // returns 'iterRequest' again.  But the real purpose
                        // is to reset the iteration position
                        // so that 'next()' would return 'iterRequest' again.
                        iterator.add(request); // inserts before 'iterRequest'.
                        inserted = true;
                    }
                }
                if (!inserted) {
                    // The priority of 'request' is the smallest, so it should be added to the end
                    // of the queue.
                    currentList.addLast(request);
                }
            }
        }
        blockingQueueSize++;
    }

    /**
     * Try to pull one async request with specific key from blocking queue to active queue.
     *
     * @param key The key to release, the other records with this key is no longer blocking.
     * @return The first record context with the same key in blocking queue, null if no such record.
     */
    @Nullable
    AsyncRequest<K> unblockOneByKey(K key) {
        if (!blockingQueue.containsKey(key)) {
            return null;
        }

        AsyncRequest<K> asyncRequest = blockingQueue.get(key).removeFirst();
        if (blockingQueue.get(key).isEmpty()) {
            blockingQueue.remove(key);
        }
        blockingQueueSize--;
        return asyncRequest;
    }

    /**
     * Get the number of async requests of blocking queue in constant-time.
     *
     * @return the number of async requests of blocking queue.
     */
    int blockingQueueSize() {
        return blockingQueueSize;
    }

    /**
     * Get the number of different keys in blocking queue.
     *
     * @return the number of different keys in blocking queue.
     */
    int blockingKeyNum() {
        return blockingQueue.size();
    }

    /**
     * Get the number of async requests of active queue in constant-time.
     *
     * @return the number of async requests of active queue.
     */
    int activeQueueSize() {
        return activeQueue.size();
    }

    /**
     * Try to pop async requests from active queue, if the size of active queue is less than N,
     * return all the requests in active queue.
     *
     * @param n The number of async requests to pop.
     * @param requestContainerInitializer Initializer for the asyncRequest container
     * @return A asyncRequestContainer which holds the popped async requests.
     */
    <REQUEST extends AsyncRequest<?>> Optional<AsyncRequestContainer<REQUEST>> popActive(
            int n, Supplier<AsyncRequestContainer<REQUEST>> requestContainerInitializer) {
        final int count = Math.min(n, activeQueue.size());
        if (count <= 0) {
            return Optional.empty();
        }
        AsyncRequestContainer<REQUEST> asyncRequestContainer = requestContainerInitializer.get();
        for (int i = 0; i < count; i++) {
            asyncRequestContainer.offer((REQUEST) activeQueue.pop());
        }
        return Optional.of(asyncRequestContainer);
    }

    @Override
    public synchronized void close() throws IOException {
        if (currentScheduledFuture != null) {
            currentScheduledFuture.cancel(true);
            currentScheduledFuture = null;
        }
    }
}
