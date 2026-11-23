/*
 * Copyright 2026 Samuel "Lord-Windy" Brown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kotharworks.wilds.server.ports;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * In-process live event fan-out for SSE subscribers. Each subscriber owns one
 * bounded queue on a virtual thread; {@link #close} enqueues a poison marker
 * into every queue and drops all subscribers so SSE handlers wake and exit
 * cleanly on server shutdown (tests never hang). Implementations must offer
 * {@link #OVERFLOW} to a subscriber queue when buffered events are dropped
 * because it is full, and {@link #CLOSED} to every queue on {@link #close}.
 */
public interface EventBusPort {

    /** Poison marker offered on {@link #close}; cursor {@code 0} is never a real event. */
    StoredWorldEvent CLOSED = new StoredWorldEvent(0, 0, "__closed__", null, null, null, null);

    /** Overflow marker prompting SSE readers to catch up from the event store. */
    StoredWorldEvent OVERFLOW = new StoredWorldEvent(0, 0, "__overflow__", null, null, null,
            null);

    /** Whether {@code event} is the {@link #OVERFLOW} marker. */
    static boolean isOverflow(StoredWorldEvent event) {
        return OVERFLOW.kind().equals(event.kind());
    }

    /** Whether {@code event} is the {@link #CLOSED} marker (a non-overflow event with cursor below 1). */
    static boolean isClosed(StoredWorldEvent event) {
        return event.cursor() < 1 && !isOverflow(event);
    }

    /**
     * Registers a per-seat subscriber queue. Only full rows whose
     * {@code seatId} equals {@code seatId} are delivered to it.
     */
    BlockingQueue<StoredWorldEvent> subscribe(int worldId, long seatId);

    /** Removes a subscriber queue (idempotent). */
    void unsubscribe(BlockingQueue<StoredWorldEvent> queue);

    /** Fans {@code events} out to every matching subscriber of the world. */
    void publish(int worldId, List<StoredWorldEvent> events);

    /**
     * Wakes and closes every active subscriber queue (poison marker) and
     * clears the subscriber list. After this, new subscriptions are refused.
     */
    void close();
}
