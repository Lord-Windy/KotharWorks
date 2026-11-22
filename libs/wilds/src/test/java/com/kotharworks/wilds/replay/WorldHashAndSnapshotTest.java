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
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.sim.DuePayload;
import com.kotharworks.wilds.sim.EntityTable;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;
import com.kotharworks.wilds.worldgen.MinimalSeedTopologyBootstrap;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 03 acceptance: any single-scalar mutation changes the {@link WorldHash}, and
 * {@code decode(encode(state))} hashes bit-for-bit identically to the original.
 */
class WorldHashAndSnapshotTest {

    /** Rich deterministic state covering every hashed structure (incl. an empty slot). */
    private static WorldState richState(long seedValue) {
        WorldState state = new WorldState(new WorldSeed(seedValue), RuleRegistry.WILDS_RULES_VERSION);
        MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(state, RuleRegistry.minimalDefault());
        PlaceId origin = new PlaceId(0, 1);
        PlaceId ridge = new PlaceId(1, 1);

        OwnerRow warden = state.owners().insertNew(id ->
                new OwnerRow(id, 7L, "Warden", 2, origin, List.of(origin)));
        state.ownersBySeatKey().put(7L, warden.id());
        OwnerRow ranger = state.owners().insertNew(id ->
                new OwnerRow(id, 8L, "Ranger", 0, origin, List.of(origin, ridge)));
        state.ownersBySeatKey().put(8L, ranger.id());

        ActorRow scout = state.actors().insertNew(id -> new ActorRow(id, warden.id(), "Scout", 1, origin,
                new LazyGauge(100_000, 0, 25, 0, 100_000),
                new ActiveTask(0, 2_000, state.allocateEventSeq(), new TaskSpec.Wait(2_000, 3), 3),
                List.of(new TaskSpec.Travel(ridge)),
                QueueFailurePolicy.ADVANCE));
        state.actors().insertNew(id -> new ActorRow(id, ranger.id(), "Forager", 0, ridge,
                new LazyGauge(50_000, 0, 0, 0, 100_000),
                null,
                List.of(),
                QueueFailurePolicy.HALT_ON_FAIL));

        long completionSeq = state.allocateEventSeq();
        state.dueEvents().push(new ScheduledEvent(
                2_000, completionSeq, 101L, new DuePayload.CompleteActorTask(scout.id())));
        long pulseSeq = state.allocateEventSeq();
        state.dueEvents().push(new ScheduledEvent(
                5_000, pulseSeq, 202L, new DuePayload.SystemPulse(new RuleId("day_tide"))));

        // One inserted-then-removed 4th place: leaves empty slot 3 at generation 2.
        PlaceRow temporary = state.places().insertNew(id ->
                new PlaceRow(id, new RuleId("trail"), "Temporary Camp", List.of()));
        assertTrue(state.places().remove(temporary.id()));

        state.advanceClockTo(1_000);
        return state;
    }

    private static WorldState decodedClone(WorldState state) {
        return SnapshotCodec.decode(SnapshotCodec.encode(state));
    }

    @Test
    void snapshotRoundTripPreservesHashBitForBit() {
        WorldState state = richState(777);
        String encoded = SnapshotCodec.encode(state);
        WorldState decoded = SnapshotCodec.decode(encoded);

        assertEquals(WorldHash.hash(state), WorldHash.hash(decoded),
                "decoded snapshot must hash identically");
        assertEquals(encoded, SnapshotCodec.encode(decoded),
                "encode(decode(encode(s))) must equal encode(s)");

        assertEquals(state.nowMs(), decoded.nowMs());
        assertEquals(state.nextEventSeq(), decoded.nextEventSeq());
        assertEquals(state.nextEventCursor(), decoded.nextEventCursor());
        assertEquals(state.places().highWaterMark(), decoded.places().highWaterMark());
        assertEquals(state.places().dumpSlots(), decoded.places().dumpSlots());
        assertEquals(state.actors().dumpSlots(), decoded.actors().dumpSlots());
        assertEquals(state.owners().dumpSlots(), decoded.owners().dumpSlots());
        assertEquals(state.ownersBySeatKey(), decoded.ownersBySeatKey());
        assertEquals(state.dueEvents().snapshotOrderedList(), decoded.dueEvents().snapshotOrderedList());
    }

    @Test
    void singleScalarMutationsChangeHash() {
        WorldState state = richState(777);
        long originalHash = WorldHash.hash(state);

        // nowMs + 1
        WorldState clockBumped = decodedClone(state);
        clockBumped.advanceClockTo(clockBumped.nowMs() + 1);
        assertNotEquals(originalHash, WorldHash.hash(clockBumped), "nowMs");

        // nextEventSeq + 1
        WorldState seqBumped = decodedClone(state);
        seqBumped.restoreCounters(seqBumped.nowMs(), seqBumped.nextEventSeq() + 1,
                seqBumped.nextEventCursor());
        assertNotEquals(originalHash, WorldHash.hash(seqBumped), "nextEventSeq");

        // nextEventCursor + 1
        WorldState cursorBumped = decodedClone(state);
        cursorBumped.restoreCounters(cursorBumped.nowMs(), cursorBumped.nextEventSeq(),
                cursorBumped.nextEventCursor() + 1);
        assertNotEquals(originalHash, WorldHash.hash(cursorBumped), "nextEventCursor");

        // ActorRow.revision + 1
        WorldState revisionBumped = decodedClone(state);
        ActorRow scout = revisionBumped.actors().getOrNull(new ActorId(0, 1));
        assertTrue(scout != null);
        revisionBumped.actors().replace(new ActorRow(scout.id(), scout.ownerId(), scout.label(),
                scout.revision() + 1, scout.placeId(), scout.energyMilli(), scout.activeTask(),
                scout.pendingQueue(), scout.failPolicy()));
        assertNotEquals(originalHash, WorldHash.hash(revisionBumped), "ActorRow.revision");

        // LazyGauge.anchorMilli + 1
        WorldState gaugeBumped = decodedClone(state);
        ActorRow forager = gaugeBumped.actors().getOrNull(new ActorId(1, 1));
        assertTrue(forager != null);
        LazyGauge gauge = forager.energyMilli();
        gaugeBumped.actors().replace(new ActorRow(forager.id(), forager.ownerId(), forager.label(),
                forager.revision(), forager.placeId(),
                new LazyGauge(gauge.anchorMilli() + 1, gauge.anchorTimeMs(), gauge.rateMilliPerSec(),
                        gauge.minMilli(), gauge.maxMilli()),
                forager.activeTask(), forager.pendingQueue(), forager.failPolicy()));
        assertNotEquals(originalHash, WorldHash.hash(gaugeBumped), "LazyGauge.anchorMilli");

        // Bumped empty-slot generation (slot 3: generation 2 -> 3)
        WorldState generationBumped = decodedClone(state);
        List<EntityTable.SlotDump<PlaceId, PlaceRow>> bumped = new ArrayList<>(
                generationBumped.places().dumpSlots());
        assertTrue(bumped.size() == 4 && bumped.get(3).row() == null && bumped.get(3).generation() == 2);
        bumped.set(3, new EntityTable.SlotDump<>(3, 3, null));
        generationBumped.places().restoreSlots(bumped);
        assertNotEquals(originalHash, WorldHash.hash(generationBumped), "empty-slot generation");

        // ScheduledEvent.seq + 1 (bump the max-seq event; no collision)
        WorldState eventSeqBumped = decodedClone(state);
        List<ScheduledEvent> events = new ArrayList<>(eventSeqBumped.dueEvents().snapshotOrderedList());
        ScheduledEvent last = events.get(events.size() - 1);
        events.set(events.size() - 1, new ScheduledEvent(
                last.dueAtMs(), last.seq() + 1, last.cancellationToken(), last.payload()));
        eventSeqBumped.dueEvents().restoreFrom(events);
        assertNotEquals(originalHash, WorldHash.hash(eventSeqBumped), "ScheduledEvent.seq");
    }

    @Test
    void differentSeedChangesHash() {
        WorldState first = richState(777);
        WorldState second = richState(778);
        assertNotEquals(WorldHash.hash(first), WorldHash.hash(second));
    }

    @Test
    void identicalRichStatesHashIdentically() {
        assertEquals(WorldHash.hash(richState(777)), WorldHash.hash(richState(777)));
    }

    @Test
    void decodeRejectsWrongFormatTag() {
        String good = SnapshotCodec.encode(richState(777));
        String tampered = good.replace("wilds-snapshot-v1", "wilds-snapshot-v0");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(tampered));
    }

    @Test
    void snapshotRoundTripPreservesSubsequentIdAndCounterAllocation() {
        WorldState live = richState(777);
        // Add two more places (slots 3 [reused gen 2] and 4 [gen 1]), then remove 4 and 1 out of order
        PlaceRow extraA = live.places().insertNew(
                id -> new PlaceRow(id, new RuleId("camp"), "Outpost A", List.of()));
        PlaceRow extraB = live.places().insertNew(
                id -> new PlaceRow(id, new RuleId("camp"), "Outpost B", List.of()));
        assertEquals(new PlaceId(3, 2), extraA.id());
        assertEquals(new PlaceId(4, 1), extraB.id());
        assertTrue(live.places().remove(extraB.id()));
        assertTrue(live.places().remove(extraA.id()));

        WorldState restored = SnapshotCodec.decode(SnapshotCodec.encode(live));
        assertEquals(WorldHash.hash(live), WorldHash.hash(restored));

        // Subsequent min-index slot allocations, event seqs, cursors, and heap polls must match 1:1
        for (int step = 0; step < 3; step++) {
            String label = "Reclaimed " + step;
            PlaceRow fromLive = live.places().insertNew(
                    id -> new PlaceRow(id, new RuleId("trail"), label, List.of()));
            PlaceRow fromRestored = restored.places().insertNew(
                    id -> new PlaceRow(id, new RuleId("trail"), label, List.of()));
            assertEquals(fromLive.id(), fromRestored.id());
        }
        assertEquals(live.allocateEventSeq(), restored.allocateEventSeq());
        assertEquals(live.allocateEventCursor(), restored.allocateEventCursor());
        assertEquals(live.dueEvents().poll(), restored.dueEvents().poll());
        assertEquals(WorldHash.hash(live), WorldHash.hash(restored));
    }

    @Test
    void lengthPrefixedStringsNeverCollideAcrossFieldBoundaries() {
        WorldState abThenC = new WorldState(new WorldSeed(42), RuleRegistry.WILDS_RULES_VERSION);
        MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(
                abThenC, RuleRegistry.minimalDefault());
        PlaceId home = new PlaceId(0, 1);
        OwnerRow owner1 = abThenC.owners().insertNew(
                id -> new OwnerRow(id, 1L, "ab", 0, home, List.of(home)));
        abThenC.ownersBySeatKey().put(1L, owner1.id());
        OwnerRow owner2 = abThenC.owners().insertNew(
                id -> new OwnerRow(id, 2L, "c", 0, home, List.of(home)));
        abThenC.ownersBySeatKey().put(2L, owner2.id());

        WorldState aThenBc = new WorldState(new WorldSeed(42), RuleRegistry.WILDS_RULES_VERSION);
        MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(
                aThenBc, RuleRegistry.minimalDefault());
        OwnerRow alt1 = aThenBc.owners().insertNew(
                id -> new OwnerRow(id, 1L, "a", 0, home, List.of(home)));
        aThenBc.ownersBySeatKey().put(1L, alt1.id());
        OwnerRow alt2 = aThenBc.owners().insertNew(
                id -> new OwnerRow(id, 2L, "bc", 0, home, List.of(home)));
        aThenBc.ownersBySeatKey().put(2L, alt2.id());

        assertNotEquals(WorldHash.hash(abThenC), WorldHash.hash(aThenBc));

        // Unpaired UTF-16 surrogates constructed in memory must throw rather than hash to '?'
        WorldState malformed = new WorldState(new WorldSeed(42), RuleRegistry.WILDS_RULES_VERSION);
        MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(
                malformed, RuleRegistry.minimalDefault());
        OwnerRow broken = malformed.owners().insertNew(
                id -> new OwnerRow(id, 1L, "bad\uD800", 0, home, List.of(home)));
        malformed.ownersBySeatKey().put(1L, broken.id());
        assertThrows(IllegalArgumentException.class, () -> WorldHash.hash(malformed));
    }

    @Test
    void decodeRejectsInconsistentSeatKeysDanglingReferencesAndStaleSeqCounters() {
        String baseline = SnapshotCodec.encode(richState(777));

        // Duplicate key in ownersBySeatKey array
        String dupSeatKeyEntry = baseline.replace(
                "[[7,\"own:0:1\"],[8,\"own:1:1\"]]",
                "[[7,\"own:0:1\"],[7,\"own:1:1\"]]");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(dupSeatKeyEntry));

        // Missing entry in ownersBySeatKey relative to live owners
        String missingSeatKeyEntry = baseline.replace(
                "[[7,\"own:0:1\"],[8,\"own:1:1\"]]",
                "[[7,\"own:0:1\"]]");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(missingSeatKeyEntry));

        // Duplicate externalSeatKey across distinct live OwnerRows
        String dupOwnerSeat = baseline
                .replace("\"externalSeatKey\":8", "\"externalSeatKey\":7")
                .replace("[[7,\"own:0:1\"],[8,\"own:1:1\"]]", "[[7,\"own:1:1\"]]");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(dupOwnerSeat));

        // nextEventSeq <= max dueEvent.seq (richState has max seq = 3, nextEventSeq = 4)
        String staleNextSeq = baseline.replace("\"nextEventSeq\":4", "\"nextEventSeq\":3");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(staleNextSeq));

        // Dangling actor placeId pointing at removed slot 3
        String danglingPlace = baseline.replace("\"placeId\":\"plc:0:1\"", "\"placeId\":\"plc:3:2\"");
        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.decode(danglingPlace));
    }

    @Test
    void worldStateCounterExhaustionThrowsInsteadOfWrapping() {
        WorldState state = new WorldState(new WorldSeed(1), RuleRegistry.WILDS_RULES_VERSION);
        state.restoreCounters(0L, Long.MAX_VALUE, Long.MAX_VALUE);
        assertThrows(IllegalStateException.class, state::allocateEventSeq);
        assertThrows(IllegalStateException.class, state::allocateEventCursor);
    }
}
