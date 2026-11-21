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
package com.kotharworks.wilds.server.adapters.memory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.kotharworks.wilds.server.ports.EventBusPort;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;

/**
 * In-memory {@link EventBusPort}: copy-on-write subscriber list, one bounded
 * queue per subscriber, seat-scoped delivery. {@link #close} pushes a poison
 * marker into every queue so blocking SSE pollers wake immediately and exit —
 * server shutdown never hangs a reader.
 */
public final class InMemoryEventBus implements EventBusPort {

    /** Capacity of each subscriber queue; overflow drops (clients replay from the store). */
    public static final int QUEUE_CAPACITY = 1024;

    /** Poison marker enqueued on {@link #close}; cursor {@code 0} is never a real event. */
    public static final StoredWorldEvent CLOSED = new StoredWorldEvent(0, 0, "__closed__", null,
            null, null, null);

    private record Subscriber(int worldId, long seatId, BlockingQueue<StoredWorldEvent> queue) {
    }

    private final CopyOnWriteArrayList<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    @Override
    public BlockingQueue<StoredWorldEvent> subscribe(int worldId, long seatId) {
        if (closed) {
            throw new IllegalStateException("event bus is closed");
        }
        BlockingQueue<StoredWorldEvent> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        subscribers.add(new Subscriber(worldId, seatId, queue));
        return queue;
    }

    @Override
    public void unsubscribe(BlockingQueue<StoredWorldEvent> queue) {
        Objects.requireNonNull(queue, "queue");
        subscribers.removeIf(subscriber -> subscriber.queue() == queue);
    }

    @Override
    public void publish(int worldId, List<StoredWorldEvent> events) {
        if (closed || events.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            if (subscriber.worldId() != worldId) {
                continue;
            }
            for (StoredWorldEvent event : events) {
                if (event.seatId() == null || !event.seatId().equals(subscriber.seatId())) {
                    continue;
                }
                subscriber.queue().offer(event);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        List<Subscriber> current = List.copyOf(subscribers);
        subscribers.clear();
        for (Subscriber subscriber : current) {
            subscriber.queue().offer(CLOSED);
        }
    }
}
