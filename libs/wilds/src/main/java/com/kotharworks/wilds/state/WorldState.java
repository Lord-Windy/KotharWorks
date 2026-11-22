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

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.sim.DueEventHeap;
import com.kotharworks.wilds.sim.EntityTable;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Mutable engine-internal root of the neutral simulation state: flat entity tables
 * addressed by typed generational ids, a seat-key index, and the due-event heap.
 * Never a record — the engine mutates this in place under a single-writer discipline;
 * snapshots and hashes are produced by the {@code com.kotharworks.wilds.replay} codecs.
 */
public final class WorldState {

    private final WorldSeed seed;
    private final int rulesVersion;
    private long nowMs;
    private long nextEventSeq;
    private long nextEventCursor;
    private final EntityTable<OwnerId, OwnerRow> owners;
    private final EntityTable<PlaceId, PlaceRow> places;
    private final EntityTable<ActorId, ActorRow> actors;
    private final TreeMap<Long, OwnerId> ownersBySeatKey;
    private final DueEventHeap dueEvents;

    public WorldState(WorldSeed seed, int rulesVersion) {
        this.seed = Objects.requireNonNull(seed, "seed");
        if (rulesVersion < 1) {
            throw new IllegalArgumentException("rulesVersion must be >= 1: " + rulesVersion);
        }
        this.rulesVersion = rulesVersion;
        this.nowMs = 0L;
        this.nextEventSeq = 1L;
        this.nextEventCursor = 1L;
        this.owners = new EntityTable<>(OwnerId::new, OwnerRow::id);
        this.places = new EntityTable<>(PlaceId::new, PlaceRow::id);
        this.actors = new EntityTable<>(ActorId::new, ActorRow::id);
        this.ownersBySeatKey = new TreeMap<>();
        this.dueEvents = new DueEventHeap();
    }

    public WorldSeed seed() {
        return seed;
    }

    public int rulesVersion() {
        return rulesVersion;
    }

    public long nowMs() {
        return nowMs;
    }

    /** Monotonic counter feeding {@code ScheduledEvent.seq} and {@code ActiveTask.heapTicket}. */
    public long nextEventSeq() {
        return nextEventSeq;
    }

    /**
     * Monotonic counter feeding {@code WorldEvent.cursor} (Ticket 04/08): persisted in
     * state so a checkpoint + WAL-tail replay produces identical event cursors.
     */
    public long nextEventCursor() {
        return nextEventCursor;
    }

    /** Allocates the next strictly ascending event seq (post-increment: returns, then +1). */
    public long allocateEventSeq() {
        if (nextEventSeq == Long.MAX_VALUE) {
            throw new IllegalStateException("event seq space exhausted");
        }
        return nextEventSeq++;
    }

    /** Allocates the next strictly ascending event cursor (post-increment: returns, then +1). */
    public long allocateEventCursor() {
        if (nextEventCursor == Long.MAX_VALUE) {
            throw new IllegalStateException("event cursor space exhausted");
        }
        return nextEventCursor++;
    }

    /** Advances the logical clock; time never moves backward. */
    public void advanceClockTo(long newNowMs) {
        if (newNowMs < nowMs) {
            throw new IllegalStateException("clock must not move backward: " + newNowMs
                    + " < " + nowMs);
        }
        nowMs = newNowMs;
    }

    /**
     * Restores the clock and counters from a snapshot (replay is a different package,
     * hence public). Validation is structural ({@code nowMs >= 0}, counters {@code >= 1});
     * unlike {@link #advanceClockTo} it may move time backward during a restore.
     */
    public void restoreCounters(long restoredNowMs, long restoredNextEventSeq,
                                long restoredNextEventCursor) {
        if (restoredNowMs < 0) {
            throw new IllegalArgumentException("restoredNowMs must be >= 0: " + restoredNowMs);
        }
        if (restoredNextEventSeq < 1) {
            throw new IllegalArgumentException(
                    "restoredNextEventSeq must be >= 1: " + restoredNextEventSeq);
        }
        if (restoredNextEventCursor < 1) {
            throw new IllegalArgumentException(
                    "restoredNextEventCursor must be >= 1: " + restoredNextEventCursor);
        }
        this.nowMs = restoredNowMs;
        this.nextEventSeq = restoredNextEventSeq;
        this.nextEventCursor = restoredNextEventCursor;
    }

    public EntityTable<OwnerId, OwnerRow> owners() {
        return owners;
    }

    public EntityTable<PlaceId, PlaceRow> places() {
        return places;
    }

    public EntityTable<ActorId, ActorRow> actors() {
        return actors;
    }

    /** Live mutable seat-key index ({@code externalSeatKey -> owner id}), ascending by key. */
    public TreeMap<Long, OwnerId> ownersBySeatKey() {
        return ownersBySeatKey;
    }

    public DueEventHeap dueEvents() {
        return dueEvents;
    }

    /**
     * Restores and validates the seat-key index against the live owner rows; used by
     * snapshot restore. Rejects duplicate {@code externalSeatKey}s across live owners
     * and any mismatch between {@code seatKeys} and the live {@link #owners()} table.
     */
    public void restoreSeatKeys(Map<Long, OwnerId> seatKeys) {
        Objects.requireNonNull(seatKeys, "seatKeys");
        TreeMap<Long, OwnerId> expected = new TreeMap<>();
        owners.forEach(owner -> {
            OwnerId duplicate = expected.put(owner.externalSeatKey(), owner.id());
            if (duplicate != null) {
                throw new IllegalArgumentException("duplicate externalSeatKey "
                        + owner.externalSeatKey() + " across owners "
                        + duplicate.toWire() + " and " + owner.id().toWire());
            }
        });
        TreeMap<Long, OwnerId> provided = new TreeMap<>();
        for (Map.Entry<Long, OwnerId> entry : seatKeys.entrySet()) {
            Long key = Objects.requireNonNull(entry.getKey(), "seatKeys contains a null key");
            OwnerId value = Objects.requireNonNull(entry.getValue(),
                    "seatKeys contains a null owner id");
            provided.put(key, value);
        }
        if (!expected.equals(provided)) {
            throw new IllegalArgumentException(
                    "ownersBySeatKey does not match live owners: expected "
                            + expected + " but got " + provided);
        }
        ownersBySeatKey.clear();
        ownersBySeatKey.putAll(provided);
    }
}
