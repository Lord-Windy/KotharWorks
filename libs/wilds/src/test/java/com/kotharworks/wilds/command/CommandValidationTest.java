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
package com.kotharworks.wilds.command;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.replay.SnapshotCodec;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;
import com.kotharworks.wilds.worldgen.MinimalSeedTopologyBootstrap;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 04 Phase-1 non-mutation proof: every rejection family leaves the world hash
 * and both monotonic counters ({@code nextEventSeq}, {@code nextEventCursor}) 100%
 * unchanged, {@code apply} and {@code validatePhase1} alike.
 */
class CommandValidationTest {

    private static final WorldSeed SEED = new WorldSeed(99L);

    private static WildsEngine engineWithAsh() {
        WildsEngine engine = new WildsEngine(SEED);
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout")));
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.RegisterOwner(2L, "Bram", "Bram's Walker")));
        return engine;
    }

    /** World digest + both counters, taken through the canonical snapshot codec. */
    private static long[] counters(WildsEngine engine) {
        WorldState snapshot = SnapshotCodec.decode(engine.snapshotJson());
        return new long[] {engine.worldHash(), snapshot.nextEventSeq(),
                snapshot.nextEventCursor()};
    }

    private static void assertRejectionUnmutates(WildsEngine engine, WorldCommand command,
                                                 ErrorCode expectedCode,
                                                 Long expectedCurrentRevision) {
        long[] before = counters(engine);
        Phase1Result result = engine.apply(command);
        Phase1Result.Rejected rejection = assertInstanceOf(Phase1Result.Rejected.class, result,
                () -> "expected rejection " + expectedCode + " for " + command);
        assertEquals(expectedCode, rejection.errorCode());
        if (expectedCurrentRevision != null) {
            assertEquals(expectedCurrentRevision.longValue(), rejection.currentRevision());
        }
        long[] after = counters(engine);
        assertEquals(before[0], after[0], "worldHash must be unchanged by a rejection");
        assertEquals(before[1], after[1], "nextEventSeq must be unchanged by a rejection");
        assertEquals(before[2], after[2], "nextEventCursor must be unchanged by a rejection");
    }

    @Test
    void forbiddenOwnerCommandIsRejectedWithoutMutation() {
        WildsEngine engine = engineWithAsh();
        OwnerId ashOwner = engine.view().owners().get(0).id();
        OwnerId bramOwner = engine.view().owners().get(1).id();
        ActorId ashActor = engine.view().actors().get(0).id();

        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(bramOwner, ashActor, null,
                        List.of(new TaskSpec.Wait(2_000, 1))),
                ErrorCode.FORBIDDEN, null);
    }

    @Test
    void staleRevisionIsRejectedWithCurrentRevision() {
        WildsEngine engine = engineWithAsh();
        OwnerId ashOwner = engine.view().owners().get(0).id();
        ActorRow ashActor = engine.view().actors().get(0);
        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor.id(),
                        ashActor.revision() + 99, List.of(new TaskSpec.Wait(2_000, 1))),
                ErrorCode.STALE_STATE, ashActor.revision());
    }

    @Test
    void queueFullIsRejectedWithoutMutation() {
        WildsEngine engine = engineWithAsh();
        OwnerId ashOwner = engine.view().owners().get(0).id();
        ActorId ashActor = engine.view().actors().get(0).id();
        List<TaskSpec> six = List.of(
                new TaskSpec.Wait(2_000, 1), new TaskSpec.Wait(2_001, 1),
                new TaskSpec.Wait(2_002, 1), new TaskSpec.Wait(2_003, 1),
                new TaskSpec.Wait(2_004, 1), new TaskSpec.Wait(2_005, 1));

        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor, null, six),
                ErrorCode.QUEUE_FULL, null);
        assertRejectionUnmutates(engine,
                new WorldCommand.ReplacePendingQueue(ashOwner, ashActor, null, six),
                ErrorCode.QUEUE_FULL, null);
    }

    @Test
    void invalidArgumentsAreRejectedWithoutMutation() {
        WildsEngine engine = engineWithAsh();

        assertRejectionUnmutates(engine,
                new WorldCommand.RegisterOwner(3L, "   ", "Label"),
                ErrorCode.INVALID_ARGUMENT, 0L);
        assertRejectionUnmutates(engine,
                new WorldCommand.RegisterOwner(3L, "Cora", ""),
                ErrorCode.INVALID_ARGUMENT, 0L);

        OwnerId ashOwner = engine.view().owners().get(0).id();
        ActorId ashActor = engine.view().actors().get(0).id();
        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor, null,
                        List.of(new TaskSpec.Wait(500, 1))),
                ErrorCode.INVALID_ARGUMENT, null);
        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor, null,
                        List.of(new TaskSpec.Wait(1_000, 11))),
                ErrorCode.INVALID_ARGUMENT, null);
    }

    @Test
    void duplicateSeatIsRejectedWithoutMutation() {
        WildsEngine engine = engineWithAsh();
        assertRejectionUnmutates(engine,
                new WorldCommand.RegisterOwner(1L, "Impostor", "Fake"),
                ErrorCode.DUPLICATE_SEAT, 0L);
    }

    @Test
    void emptyTasksAndUnknownTargetsAreRejectedWithoutMutation() {
        WildsEngine engine = engineWithAsh();
        OwnerId ashOwner = engine.view().owners().get(0).id();
        ActorId ashActor = engine.view().actors().get(0).id();

        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor, null, List.of()),
                ErrorCode.EMPTY_TASKS, null);
        assertRejectionUnmutates(engine,
                new WorldCommand.ReplacePendingQueue(ashOwner, ashActor, null, List.of()),
                ErrorCode.EMPTY_TASKS, null);
        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, new ActorId(9, 1), null,
                        List.of(new TaskSpec.Wait(2_000, 1))),
                ErrorCode.UNKNOWN_TARGET, 0L);
        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor, null,
                        List.of(new TaskSpec.Travel(new PlaceId(9, 1)))),
                ErrorCode.UNKNOWN_TARGET, null);
    }

    @Test
    void unknownRuleIsRejectedWithoutMutation() {
        RuleRegistry waitOnlyRules = new RuleRegistry(
                Map.of(
                        new RuleId("camp"), new RuleRegistry.PlaceKindDef(
                                new RuleId("camp"), "Camp"),
                        new RuleId("trail"), new RuleRegistry.PlaceKindDef(
                                new RuleId("trail"), "Trail"),
                        new RuleId("landmark"), new RuleRegistry.PlaceKindDef(
                                new RuleId("landmark"), "Landmark")),
                Map.of(new RuleId("wait"), new RuleRegistry.TaskKindDef(
                        new RuleId("wait"), "Wait", 1_000L, 3_600_000L, 10)));
        WildsEngine engine = new WildsEngine(SEED, waitOnlyRules,
                MinimalSeedTopologyBootstrap.INSTANCE);
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout")));
        OwnerId ashOwner = engine.view().owners().get(0).id();
        ActorId ashActor = engine.view().actors().get(0).id();

        assertRejectionUnmutates(engine,
                new WorldCommand.EnqueueTasks(ashOwner, ashActor, null,
                        List.of(new TaskSpec.Travel(new PlaceId(1, 1)))),
                ErrorCode.UNKNOWN_RULE, null);
    }

    @Test
    void validatePhase1AloneNeverMutatesAnything() {
        WildsEngine engine = engineWithAsh();
        engine.drainEvents(); // registration events are pre-existing output, not mutations
        OwnerId ashOwner = engine.view().owners().get(0).id();
        ActorId ashActor = engine.view().actors().get(0).id();
        long[] before = counters(engine);

        assertTrue(engine.validatePhase1(new WorldCommand.RegisterOwner(7L, "Cora", "Probe"))
                instanceof Phase1Result.Accepted);
        assertTrue(engine.validatePhase1(new WorldCommand.EnqueueTasks(ashOwner, ashActor,
                null, List.of(new TaskSpec.Travel(new PlaceId(9, 1)))))
                instanceof Phase1Result.Rejected);

        long[] after = counters(engine);
        assertEquals(before[0], after[0]);
        assertEquals(before[1], after[1]);
        assertEquals(before[2], after[2]);
        assertTrue(engine.drainEvents().isEmpty(), "validation must not emit events");
    }
}
