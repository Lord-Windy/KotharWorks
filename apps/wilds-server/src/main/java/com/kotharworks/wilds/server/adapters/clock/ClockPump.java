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
package com.kotharworks.wilds.server.adapters.clock;

import java.util.Objects;

import com.kotharworks.wilds.server.domain.WorldHost;

/**
 * The live pump adapter: one virtual thread sleeps {@code pumpIntervalMs} of
 * wall time and enqueues a tick into the {@link WorldHost} mailbox (the writer
 * thread alone decides whether a {@code CLOCK_ADVANCE} anchor is warranted, so
 * there is zero idle WAL spam). {@code close} interrupts the sleeper; tests run
 * a {@code 50ms} interval for fast integration passes.
 */
public final class ClockPump {

    private final WorldHost worldHost;
    private final long pumpIntervalMs;
    private volatile Thread pumpThread;

    public ClockPump(WorldHost worldHost, long pumpIntervalMs) {
        this.worldHost = Objects.requireNonNull(worldHost, "worldHost");
        if (pumpIntervalMs < 1) {
            throw new IllegalArgumentException("pumpIntervalMs must be >= 1: " + pumpIntervalMs);
        }
        this.pumpIntervalMs = pumpIntervalMs;
    }

    /** Starts the pump thread (idempotent: a running pump is left alone). */
    public synchronized void start() {
        if (pumpThread != null) {
            return;
        }
        pumpThread = Thread.ofVirtual().name("wilds-clock-pump").start(this::loop);
    }

    private void loop() {
        while (true) {
            try {
                Thread.sleep(pumpIntervalMs);
            } catch (InterruptedException e) {
                return;
            }
            worldHost.tick();
        }
    }

    /** Stops the pump by interrupting the sleeper (idempotent). */
    public synchronized void close() {
        Thread thread = pumpThread;
        pumpThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Configured wall-time interval between ticks. */
    public long pumpIntervalMs() {
        return pumpIntervalMs;
    }
}
