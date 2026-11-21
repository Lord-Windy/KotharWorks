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
package com.kotharworks.wilds.task;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 04 two-phase task-queue acceptance: optimistic chain queuing, fail-soft
 * failure policies, O(1) heap-ticket cancellation, integer permille energy penalty,
 * and multi-batch waits.
 */
class TaskQueueMechanicsTest {

    private static final WorldSeed SEED = new WorldSeed(0x5EED0004L);

    private static OwnerId registerOwner(WildsEngine engine, long seat, String name,
                                         String label) {
        assertInstanceOf(Phase1Result.Accepted.class,
                engine.apply(new WorldCommand.RegisterOwner(seat, name, label)));
        return engine.view().findOwnerBySeatKey(seat).orElseThrow().id();
    }

    private static ActorRow actor(WildsEngine engine) {
        return engine.view().actors().get(0);
    }

    private static void runToIdle(WildsEngine engine) {
        while (engine.peekNextDueMs().isPresent()) {
            engine.advanceToNextEvent();
        }
    }

    private static List<WorldEvent> drain(WildsEngine engine) {
        return engine.drainEvents();
    }

    @Test
    void optimisticChainQueueWalksTwoHopsAndDiscoversAhead() {
        WildsEngine engine = new WildsEngine(SEED);
        OwnerId owner = registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();

        // Phase 1 must accept the chain WITHOUT the actor standing adjacent to plc:2:1.
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new TaskSpec.Travel(new PlaceId(1, 1)),
                                new TaskSpec.Travel(new PlaceId(2, 1))))));

        engine.advanceToNextEvent(); // leg 1: plc:0:1 -> plc:1:1
        List<WorldEvent> leg1Events = drain(engine);
        int discoveredIdx = -1;
        int secondLegStartIdx = -1;
        for (int i = 0; i < leg1Events.size(); i++) {
            if (leg1Events.get(i) instanceof WorldEvent.PlaceDiscovered discovered
                    && discovered.placeId().equals(new PlaceId(2, 1))) {
                discoveredIdx = i;
            }
            if (leg1Events.get(i) instanceof WorldEvent.TaskStarted started
                    && started.task().spec() instanceof TaskSpec.Travel travel
                    && travel.destination().equals(new PlaceId(2, 1))) {
                secondLegStartIdx = i;
            }
        }
        assertTrue(discoveredIdx >= 0, "arrival at plc:1:1 must discover plc:2:1");
        assertTrue(secondLegStartIdx > discoveredIdx,
                "the second leg must start after the discovery event");

        engine.advanceToNextEvent(); // leg 2: plc:1:1 -> plc:2:1
        runToIdle(engine);
        assertEquals(new PlaceId(2, 1), actor(engine).placeId());
        assertEquals(List.of(new PlaceId(0, 1), new PlaceId(1, 1), new PlaceId(2, 1)),
                engine.view().owner(owner).orElseThrow().discoveredPlaces());
        assertTrue(drain(engine).stream().noneMatch(event -> event instanceof WorldEvent
                .PlaceDiscovered), "leg 2 arrival reveals nothing new");
    }

    @Test
    void brokenRouteUnderAdvanceSkipsFailedTravelAndStartsWait() {
        WildsEngine engine = new WildsEngine(SEED);
        OwnerId owner = registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new TaskSpec.Travel(new PlaceId(2, 1)),
                                new TaskSpec.Wait(2_000, 1)))));

        List<WorldEvent> events = drain(engine);
        WorldEvent.TaskFailed failed = null;
        WorldEvent.TaskStarted waitStarted = null;
        for (WorldEvent event : events) {
            if (event instanceof WorldEvent.TaskFailed taskFailed
                    && taskFailed.failureCode() == ErrorCode.NO_ROUTE) {
                failed = taskFailed;
            }
            if (event instanceof WorldEvent.TaskStarted started
                    && started.task().spec() instanceof TaskSpec.Wait) {
                waitStarted = started;
            }
        }
        assertTrue(failed != null, "TaskFailed(NO_ROUTE) must be emitted");
        assertTrue(waitStarted != null, "the wait must start immediately after the failure");
        assertEquals(0L, failed.occurredMs(), "NO_ROUTE must fire at t=0");
        assertEquals(0L, waitStarted.occurredMs(), "the wait must start immediately at t=0");
        assertEquals(2_000L, engine.peekNextDueMs().orElseThrow());

        engine.advanceToNextEvent();
        assertNull(actor(engine).activeTask());
        drain(engine);
    }

    @Test
    void brokenRouteUnderHaltClearsQueueAndStandsIdle() {
        WildsEngine engine = new WildsEngine(SEED);
        OwnerId owner = registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.SetFailurePolicy(owner, actor, null,
                        QueueFailurePolicy.HALT_ON_FAIL)));
        drain(engine);

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new TaskSpec.Travel(new PlaceId(2, 1)),
                                new TaskSpec.Wait(2_000, 1)))));

        List<WorldEvent> events = drain(engine);
        assertTrue(events.stream().anyMatch(event -> event instanceof WorldEvent.TaskFailed
                        taskFailed && taskFailed.failureCode() == ErrorCode.NO_ROUTE),
                "TaskFailed(NO_ROUTE) must be emitted");

        ActorRow halted = actor(engine);
        assertNull(halted.activeTask(), "actor must stand IDLE_PATIENT");
        assertTrue(halted.pendingQueue().isEmpty(), "HALT_ON_FAIL must clear the queue");
        assertEquals(TaskQueueRunner.IDLE_RATE_MILLI_PER_SEC,
                halted.energyMilli().rateMilliPerSec());
        assertTrue(engine.peekNextDueMs().isEmpty(), "no further events may be scheduled");
    }

    @Test
    void activeHeapTicketCancelSkipsStaleEntry() {
        WildsEngine engine = new WildsEngine(SEED);
        OwnerId owner = registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new TaskSpec.Wait(10_000, 3)))));
        assertEquals(10_000L, engine.advanceTo(10_000L));
        List<WorldEvent> batch1 = drain(engine);
        assertEquals(1, batch1.stream().filter(event -> event
                        instanceof WorldEvent.TaskBatchCompleted).count(),
                "exactly one batch completed at 10_000ms");
        assertEquals(20_000L, engine.peekNextDueMs().orElseThrow(),
                "the next batch must be due at 20_000ms");

        assertEquals(14_000L, engine.advanceTo(14_000L));
        long revisionBefore = actor(engine).revision();

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.CancelQueue(owner, actor, null, true)));
        List<WorldEvent> cancelEvents = drain(engine);
        assertEquals(1, cancelEvents.size());
        WorldEvent.TaskCancelled cancelled =
                assertInstanceOf(WorldEvent.TaskCancelled.class, cancelEvents.get(0));
        assertTrue(cancelled.cancelledActive());
        assertEquals(0, cancelled.clearedPendingCount());
        assertEquals(revisionBefore + 2, actor(engine).revision(),
                "cancelActive bumps revision once, the queue clear once");
        assertEquals(TaskQueueRunner.IDLE_RATE_MILLI_PER_SEC,
                actor(engine).energyMilli().rateMilliPerSec());

        assertEquals(30_000L, engine.advanceTo(30_000L));
        assertTrue(drain(engine).isEmpty(), "the stale 20_000ms entry must be ignored");
        assertNull(actor(engine).activeTask());
        assertTrue(actor(engine).pendingQueue().isEmpty());
        assertTrue(engine.peekNextDueMs().isEmpty());
    }

    @Test
    void lowEnergyTravelAppliesIntegerPermillePenalty() {
        WildsEngine engine = new WildsEngine(SEED);
        registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();

        long crossings = 0;
        boolean alternate = true;
        while (actor(engine).energyMilli().valueMilliAt(engine.nowMs()) >= 15_000L) {
            assertTrue(crossings < 2_000, "energy drain loop must terminate");
            PlaceId destination = alternate
                    ? new PlaceId(1, 1)
                    : new PlaceId(0, 1);
            alternate = !alternate;
            assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                    new WorldCommand.EnqueueTasks(actor(engine).ownerId(), actor, null,
                            List.of(new TaskSpec.Travel(destination)))));
            engine.advanceToNextEvent();
            crossings++;
        }
        assertTrue(actor(engine).energyMilli().valueMilliAt(engine.nowMs()) < 15_000L);

        // One more travel: due time must carry the 1_350 permille duration penalty.
        PlaceRow currentPlace = engine.view().place(actor(engine).placeId()).orElseThrow();
        PlaceId destination = currentPlace.edges().get(0).to();
        PlaceEdge edge = null;
        for (PlaceEdge candidate : currentPlace.edges()) {
            if (candidate.to().equals(destination)) {
                edge = candidate;
            }
        }
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(actor(engine).ownerId(), actor, null,
                        List.of(new TaskSpec.Travel(destination)))));
        long penalizedDue = engine.peekNextDueMs().orElseThrow() - engine.nowMs();
        assertEquals((edge.travelDurationMs() * 1_350L) / 1_000L, penalizedDue,
                "low-energy travel duration must be stretched by 1_350 permille");
    }

    @Test
    void multiBatchWaitCompletesEachBatchWithRisingRevision() {
        WildsEngine engine = new WildsEngine(SEED);
        OwnerId owner = registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new TaskSpec.Wait(2_000, 3)))));

        List<Long> completionTimes = new ArrayList<>();
        List<Integer> batchesLeft = new ArrayList<>();
        List<Long> revisions = new ArrayList<>();
        while (engine.peekNextDueMs().isPresent()) {
            engine.advanceToNextEvent();
            for (WorldEvent event : drain(engine)) {
                if (event instanceof WorldEvent.TaskBatchCompleted completed
                        && completed.spec() instanceof TaskSpec.Wait) {
                    completionTimes.add(event.occurredMs());
                    batchesLeft.add(completed.batchesLeftAfter());
                }
            }
            revisions.add(actor(engine).revision());
        }
        assertEquals(List.of(2_000L, 4_000L, 6_000L), completionTimes);
        assertEquals(List.of(2, 1, 0), batchesLeft);
        long previous = -1;
        for (long revision : revisions) {
            assertTrue(revision > previous, "revision must strictly increase");
            previous = revision;
        }
        assertNull(actor(engine).activeTask());
    }

    @Test
    void cancelQueueWithActiveFalseKeepsTaskRunning() {
        WildsEngine engine = new WildsEngine(SEED);
        OwnerId owner = registerOwner(engine, 1L, "Ash", "Ash's Scout");
        ActorId actor = actor(engine).id();

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new TaskSpec.Wait(9_000, 2),
                                new TaskSpec.Wait(1_500, 1)))));
        engine.advanceTo(1_000L);
        drain(engine); // registration + task-start events precede the cancel

        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.CancelQueue(owner, actor, null, false)));
        WorldEvent.TaskCancelled cancelled = (WorldEvent.TaskCancelled) drain(engine).get(0);
        assertFalse(cancelled.cancelledActive());
        assertEquals(1, cancelled.clearedPendingCount());
        ActorRow running = actor(engine);
        assertTrue(running.activeTask() != null, "the active wait must keep running");
        assertTrue(running.pendingQueue().isEmpty(), "only the pending queue was cleared");
        assertEquals(9_000L, engine.peekNextDueMs().orElseThrow());
    }
}
