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
package com.kotharworks.wilds;

import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.TaskSpec;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 04 engine facade acceptance: registration seeding, event outbox cursors,
 * immutable views, clock advancement, and snapshot-restore determinism.
 */
class WildsEngineTest {

    private static final WorldSeed SEED = new WorldSeed(0x5EED0004L);

    @Test
    void apiVersionIsPinned() {
        assertEquals(1, WildsEngine.API_VERSION);
    }

    @Test
    void registerOwnerSeedsHomeOwnerAndActor() {
        WildsEngine engine = new WildsEngine(SEED);
        Phase1Result result = engine.apply(
                new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        assertInstanceOf(Phase1Result.Accepted.class, result);

        WorldView view = engine.view();
        OwnerRow owner = view.findOwnerBySeatKey(1L).orElseThrow();
        assertEquals("Ash", owner.name());
        assertEquals(new OwnerId(0, 1), owner.id());
        assertEquals(List.of(new PlaceId(0, 1), new PlaceId(1, 1)), owner.discoveredPlaces(),
                "registration must discover the home place and its immediate neighbor");

        ActorRow actor = view.actor(new ActorId(0, 1)).orElseThrow();
        assertEquals(owner.id(), actor.ownerId());
        assertEquals("Ash's Scout", actor.label());
        assertEquals(new PlaceId(0, 1), actor.placeId());
        assertEquals(0L, actor.revision());
        assertNull(actor.activeTask(), "actor must start IDLE_PATIENT");
        assertEquals(100_000L, actor.energyMilli().valueMilliAt(engine.nowMs()));
        assertEquals(1, view.actorsOwnedBy(owner.id()).size());
    }

    @Test
    void registrationEmitsSinglePublicEventWithCursorOne() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));

        List<WorldEvent> events = engine.drainEvents();
        assertEquals(1, events.size());
        WorldEvent.OwnerRegistered registered = assertInstanceOf(WorldEvent.OwnerRegistered.class,
                events.get(0));
        assertEquals("Seat 'Ash' entered the world", registered.publicSummary());
        assertEquals(1L, registered.cursor());
        assertEquals(new ActorId(0, 1), registered.initialActorId());
        assertTrue(engine.drainEvents().isEmpty(), "second drain must be empty");
    }

    @Test
    void duplicateSeatIsRejected() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        Phase1Result rejected = engine.apply(
                new WorldCommand.RegisterOwner(1L, "Impostor", "Fake"));
        Phase1Result.Rejected rejection = assertInstanceOf(Phase1Result.Rejected.class, rejected);
        assertEquals(com.kotharworks.wilds.command.ErrorCode.DUPLICATE_SEAT,
                rejection.errorCode());
    }

    @Test
    void viewsAreDeeplyImmutableAcrossMutations() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        WorldView before = engine.view();

        engine.apply(new WorldCommand.EnqueueTasks(ownerId(before), actorId(before),
                null, List.of(new TaskSpec.Wait(2_000, 1))));
        engine.advanceTo(2_000);

        ActorRow staleActor = before.actor(new ActorId(0, 1)).orElseThrow();
        assertEquals(0L, staleActor.revision(), "old view must still report the old row");
        assertNull(staleActor.activeTask());

        WorldView after = engine.view();
        ActorRow freshActor = after.actor(new ActorId(0, 1)).orElseThrow();
        assertTrue(freshActor.revision() > 0L, "engine view must reflect the mutation");
    }

    @Test
    void advanceToClampsClockAndProcessesDueEvents() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        engine.apply(new WorldCommand.EnqueueTasks(new OwnerId(0, 1), new ActorId(0, 1),
                null, List.of(new TaskSpec.Wait(2_000, 1))));

        assertEquals(1_000L, engine.advanceTo(1_000L));
        assertEquals(1_000L, engine.nowMs());
        assertTrue(engine.view().actor(new ActorId(0, 1)).orElseThrow().activeTask() != null,
                "wait still running before its due time");

        assertEquals(3_000L, engine.advanceTo(3_000L));
        assertNull(engine.view().actor(new ActorId(0, 1)).orElseThrow().activeTask(),
                "wait completed during advanceTo");
        assertTrue(engine.peekNextDueMs().isEmpty());
    }

    @Test
    void advanceToNextEventJumpsToHeadDueTime() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        engine.apply(new WorldCommand.EnqueueTasks(new OwnerId(0, 1), new ActorId(0, 1),
                null, List.of(new TaskSpec.Wait(5_000, 1))));

        assertEquals(5_000L, engine.advanceToNextEvent());
        assertEquals(5_000L, engine.nowMs());
    }

    @Test
    void peekNextDueMsIsEmptyWhenIdle() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        assertTrue(engine.peekNextDueMs().isEmpty());
        assertThrows(java.util.NoSuchElementException.class, engine::advanceToNextEvent);
    }

    @Test
    void snapshotRestoreDrivesIdenticalCommandStreams() {
        WildsEngine continuous = new WildsEngine(SEED);
        continuous.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        continuous.apply(new WorldCommand.EnqueueTasks(new OwnerId(0, 1), new ActorId(0, 1),
                null, List.of(new TaskSpec.Travel(new PlaceId(1, 1)))));
        long edge01Ms = continuous.view().place(new PlaceId(0, 1)).orElseThrow()
                .edges().get(0).travelDurationMs();
        continuous.advanceTo(edge01Ms / 2);

        WildsEngine restored = WildsEngine.fromSnapshotJson(continuous.snapshotJson());
        assertEquals(continuous.worldHash(), restored.worldHash());
        assertEquals(continuous.nowMs(), restored.nowMs());

        List<TaskSpec> secondLeg = List.of(new TaskSpec.Wait(1_500, 2));
        for (WildsEngine engine : List.of(continuous, restored)) {
            engine.apply(new WorldCommand.EnqueueTasks(new OwnerId(0, 1), new ActorId(0, 1),
                    null, secondLeg));
            while (engine.peekNextDueMs().isPresent()) {
                engine.advanceToNextEvent();
            }
        }
        assertEquals(continuous.worldHash(), restored.worldHash());
        assertEquals(continuous.snapshotJson(), restored.snapshotJson());
    }

    @Test
    void advanceToBackwardThrows() {
        WildsEngine engine = new WildsEngine(SEED);
        engine.apply(new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout"));
        engine.advanceTo(10L);
        assertThrows(IllegalStateException.class, () -> engine.advanceTo(9L));
        assertFalse(engine.drainEvents().isEmpty(), "registration event was buffered");
    }

    private static OwnerId ownerId(WorldView view) {
        return view.owners().get(0).id();
    }

    private static ActorId actorId(WorldView view) {
        return view.actors().get(0).id();
    }
}
