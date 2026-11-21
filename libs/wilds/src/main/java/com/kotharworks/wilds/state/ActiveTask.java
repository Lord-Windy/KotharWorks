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
package com.kotharworks.wilds.state;

import java.util.Objects;

/**
 * The task an actor is currently executing, mirroring the matching entry in the
 * {@code DueEventHeap} via {@code heapTicket} (the global event seq allocated at start).
 */
public record ActiveTask(
        long startedAtMs,
        long dueAtMs,
        long heapTicket,
        TaskSpec spec,
        int batchesLeft) {

    public ActiveTask {
        Objects.requireNonNull(spec, "spec");
        if (startedAtMs > dueAtMs) {
            throw new IllegalArgumentException("startedAtMs must be <= dueAtMs: "
                    + startedAtMs + " > " + dueAtMs);
        }
        if (heapTicket < 1) {
            throw new IllegalArgumentException("heapTicket must be >= 1: " + heapTicket);
        }
        if (batchesLeft < 0) {
            throw new IllegalArgumentException("batchesLeft must be >= 0: " + batchesLeft);
        }
    }
}
