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

import java.util.Objects;

import com.kotharworks.wilds.event.WorldEvent;

/**
 * One persisted world event. Full rows carry the private summary and the
 * decoded {@link WorldEvent} payload; the public projection produced by
 * {@code WorldStorePort#readEventsSince(worldId, null, ...)} zeroes
 * {@code privateSummary} and {@code event} so public feeds structurally cannot
 * leak owner-private detail.
 *
 * @param cursor         globally monotonic event cursor (per-world idempotency
 *                       key; {@code 0} is reserved for the event-bus wake
 *                       marker — real events are always {@code >= 1})
 * @param occurredMs     logical occurrence time
 * @param kind           stable lowercase event kind (SSE {@code event:} name)
 * @param seatId         owning seat key (null on public projections)
 * @param publicSummary  world-wire safe summary; null on private-only events
 * @param privateSummary owner-private detail; null on public projections only
 * @param event          decoded engine event; null on public projections only
 */
public record StoredWorldEvent(long cursor, long occurredMs, String kind, Long seatId,
        String publicSummary, String privateSummary, WorldEvent event) {

    public StoredWorldEvent {
        Objects.requireNonNull(kind, "kind");
        if (cursor < 0) {
            throw new IllegalArgumentException("cursor must be >= 0: " + cursor);
        }
    }

    /** A full (private) row as emitted by the engine for one owning seat. */
    public static StoredWorldEvent full(long cursor, long occurredMs, String kind, long seatId,
            String publicSummary, String privateSummary, WorldEvent event) {
        return new StoredWorldEvent(cursor, occurredMs, kind, seatId, publicSummary,
                privateSummary, event);
    }

    /**
     * Wraps a live engine event into a full row for {@code seatId} (the seat key
     * owning the event's {@code scopeOwner}). The canonical lowercase kind is
     * derived here — the single source shared by the WAL event log and the SSE
     * {@code event:} names.
     */
    public static StoredWorldEvent of(WorldEvent event, long seatId) {
        Objects.requireNonNull(event, "event");
        return new StoredWorldEvent(event.cursor(), event.occurredMs(), kindOf(event), seatId,
                event.publicSummary(), event.privateSummary(), event);
    }

    /** The canonical lowercase kind of an engine event (SSE {@code event:} name). */
    public static String kindOf(WorldEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case WorldEvent.OwnerRegistered _ -> "owner_registered";
            case WorldEvent.TaskStarted _ -> "task_started";
            case WorldEvent.TaskBatchCompleted _ -> "task_batch_completed";
            case WorldEvent.TaskCancelled _ -> "task_cancelled";
            case WorldEvent.TaskFailed _ -> "task_failed";
            case WorldEvent.PlaceDiscovered _ -> "place_discovered";
        };
    }

    /** The public projection of a row: only cursor/time/kind/publicSummary survive. */
    public static StoredWorldEvent publicProjection(StoredWorldEvent row) {
        return new StoredWorldEvent(row.cursor, row.occurredMs, row.kind, null,
                row.publicSummary, null, null);
    }
}
