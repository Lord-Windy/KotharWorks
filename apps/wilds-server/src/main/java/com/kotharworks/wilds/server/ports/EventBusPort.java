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
 * cleanly on server shutdown (tests never hang).
 */
public interface EventBusPort {

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
