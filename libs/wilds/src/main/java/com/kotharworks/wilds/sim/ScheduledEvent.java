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
package com.kotharworks.wilds.sim;

import java.util.Objects;

/**
 * One deterministic scheduled event, ordered by {@code dueAtMs} first, then by the
 * strictly ascending global {@code seq} tie-breaker. {@code cancellationToken} lets the
 * consumer invalidate an event in O(1) at pop time (no heap search or removal).
 */
public record ScheduledEvent(
        long dueAtMs,
        long seq,
        long cancellationToken,
        DuePayload payload) implements Comparable<ScheduledEvent> {

    public ScheduledEvent {
        Objects.requireNonNull(payload, "payload");
    }

    @Override
    public int compareTo(ScheduledEvent other) {
        int c = Long.compare(dueAtMs, other.dueAtMs);
        return c != 0 ? c : Long.compare(seq, other.seq);
    }
}
