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
package com.kotharworks.wilds.replay;

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.sim.DuePayload;
import com.kotharworks.wilds.sim.EntityTable;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical 64-bit structural digest of a {@link WorldState}: the first 8 bytes
 * (big-endian) of SHA-256 over a fixed, length-prefixed, big-endian serialization of
 * every determinism-relevant scalar and structure.
 *
 * <p>Canonicalization rules: only initialized table slots {@code 0 .. highWaterMark - 1}
 * plus their slot generations are hashed (never raw array capacity), and due events are
 * hashed in {@code (dueAtMs, seq)} snapshot order (never the internal binary-heap array
 * layout), so logically equal states hash identically regardless of allocation history.
 */
public final class WorldHash {

    private static final String HASH_TAG = "wilds-worldhash-v1";

    private WorldHash() {
    }

    public static long hash(WorldState state) {
        Objects.requireNonNull(state, "state");
        MessageDigest digest = newDigest();
        Sink sink = new Sink(digest);

        sink.putTag(HASH_TAG);
        sink.putLong(state.seed().value());
        sink.putInt(state.rulesVersion());
        sink.putLong(state.nowMs());
        sink.putLong(state.nextEventSeq());
        sink.putLong(state.nextEventCursor());

        sink.putTag("owners");
        List<EntityTable.SlotDump<OwnerId, OwnerRow>> ownerSlots = state.owners().dumpSlots();
        sink.putInt(ownerSlots.size());
        for (EntityTable.SlotDump<OwnerId, OwnerRow> slot : ownerSlots) {
            sink.putInt(slot.generation());
            sink.putBoolean(slot.row() != null);
            if (slot.row() != null) {
                OwnerRow row = slot.row();
                sink.putString(row.id().toWire());
                sink.putLong(row.externalSeatKey());
                sink.putString(row.name());
                sink.putLong(row.revision());
                sink.putString(row.homePlaceId().toWire());
                sink.putInt(row.discoveredPlaces().size());
                for (PlaceId place : row.discoveredPlaces()) {
                    sink.putString(place.toWire());
                }
            }
        }

        sink.putTag("places");
        List<EntityTable.SlotDump<PlaceId, PlaceRow>> placeSlots = state.places().dumpSlots();
        sink.putInt(placeSlots.size());
        for (EntityTable.SlotDump<PlaceId, PlaceRow> slot : placeSlots) {
            sink.putInt(slot.generation());
            sink.putBoolean(slot.row() != null);
            if (slot.row() != null) {
                PlaceRow row = slot.row();
                sink.putString(row.id().toWire());
                sink.putString(row.kindId().slug());
                sink.putString(row.label());
                sink.putInt(row.edges().size());
                for (PlaceEdge edge : row.edges()) {
                    sink.putString(edge.to().toWire());
                    sink.putLong(edge.travelDurationMs());
                }
            }
        }

        sink.putTag("actors");
        List<EntityTable.SlotDump<ActorId, ActorRow>> actorSlots = state.actors().dumpSlots();
        sink.putInt(actorSlots.size());
        for (EntityTable.SlotDump<ActorId, ActorRow> slot : actorSlots) {
            sink.putInt(slot.generation());
            sink.putBoolean(slot.row() != null);
            if (slot.row() != null) {
                ActorRow row = slot.row();
                sink.putString(row.id().toWire());
                sink.putString(row.ownerId().toWire());
                sink.putString(row.placeId().toWire());
                sink.putString(row.label());
                sink.putLong(row.revision());
                LazyGauge gauge = row.energyMilli();
                sink.putLong(gauge.anchorMilli());
                sink.putLong(gauge.anchorTimeMs());
                sink.putLong(gauge.rateMilliPerSec());
                sink.putLong(gauge.minMilli());
                sink.putLong(gauge.maxMilli());
                sink.putBoolean(row.activeTask() != null);
                if (row.activeTask() != null) {
                    ActiveTask task = row.activeTask();
                    sink.putLong(task.startedAtMs());
                    sink.putLong(task.dueAtMs());
                    sink.putLong(task.heapTicket());
                    putSpec(sink, task.spec());
                    sink.putInt(task.batchesLeft());
                }
                sink.putInt(row.pendingQueue().size());
                for (TaskSpec spec : row.pendingQueue()) {
                    putSpec(sink, spec);
                }
                sink.putInt(row.failPolicy().ordinal());
            }
        }

        sink.putTag("seatKeys");
        sink.putInt(state.ownersBySeatKey().size());
        for (Map.Entry<Long, OwnerId> entry : state.ownersBySeatKey().entrySet()) {
            sink.putLong(entry.getKey());
            sink.putString(entry.getValue().toWire());
        }

        sink.putTag("dueEvents");
        List<ScheduledEvent> events = state.dueEvents().snapshotOrderedList();
        sink.putInt(events.size());
        for (ScheduledEvent event : events) {
            sink.putLong(event.dueAtMs());
            sink.putLong(event.seq());
            sink.putLong(event.cancellationToken());
            switch (event.payload()) {
                case DuePayload.CompleteActorTask(ActorId actorId) -> {
                    sink.putTag("completeActorTask");
                    sink.putString(actorId.toWire());
                }
                case DuePayload.SystemPulse(RuleId pulseId) -> {
                    sink.putTag("systemPulse");
                    sink.putString(pulseId.slug());
                }
            }
        }

        byte[] sha = digest.digest();
        long result = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            result = (result << 8) | (sha[i] & 0xFFL);
        }
        return result;
    }

    private static void putSpec(Sink sink, TaskSpec spec) {
        switch (spec) {
            case TaskSpec.Wait(long durationMs, int batches) -> {
                sink.putTag("wait");
                sink.putLong(durationMs);
                sink.putInt(batches);
            }
            case TaskSpec.Travel(PlaceId destination) -> {
                sink.putTag("travel");
                sink.putString(destination.toWire());
            }
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", missing);
        }
    }

    /** Big-endian, length-prefixed primitive sink over a {@link MessageDigest}. */
    private static final class Sink {

        private final MessageDigest digest;
        private final byte[] scratch = new byte[Long.BYTES];

        Sink(MessageDigest digest) {
            this.digest = digest;
        }

        void putLong(long value) {
            scratch[0] = (byte) (value >>> 56);
            scratch[1] = (byte) (value >>> 48);
            scratch[2] = (byte) (value >>> 40);
            scratch[3] = (byte) (value >>> 32);
            scratch[4] = (byte) (value >>> 24);
            scratch[5] = (byte) (value >>> 16);
            scratch[6] = (byte) (value >>> 8);
            scratch[7] = (byte) value;
            digest.update(scratch, 0, Long.BYTES);
        }

        void putInt(int value) {
            scratch[0] = (byte) (value >>> 24);
            scratch[1] = (byte) (value >>> 16);
            scratch[2] = (byte) (value >>> 8);
            scratch[3] = (byte) value;
            digest.update(scratch, 0, Integer.BYTES);
        }

        void putBoolean(boolean value) {
            digest.update((byte) (value ? 1 : 0));
        }

        void putString(String value) {
            requireWellFormedUtf16(value);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            putInt(bytes.length);
            digest.update(bytes);
        }

        void putTag(String tag) {
            putString(tag);
        }

        private static void requireWellFormedUtf16(String value) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        throw new IllegalArgumentException(
                                "unpaired UTF-16 high surrogate in WorldState string");
                    }
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    throw new IllegalArgumentException(
                            "unpaired UTF-16 low surrogate in WorldState string");
                }
            }
        }
    }
}
