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

import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.observe.ObservationProjector;
import com.kotharworks.wilds.observe.OwnerObservation;
import com.kotharworks.wilds.observe.PlaceDto;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.seed.SeededRng;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.worldgen.StubDescriptionComposer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 04 seeded fuzz acceptance: 5,000 random valid/invalid commands and time jumps
 * across two independent engines running the identical op stream from step 0 plus a
 * mid-run {@code SnapshotCodec} clone at step 2,500 — zero uncaught exceptions,
 * step-by-step {@code WorldEvent} cursor monotonicity &amp; equality, Phase-1
 * non-mutation on every random rejection, {@code CommandCodec} round-trip parity,
 * fog-of-war invariants, and organic coverage of every live rejection family.
 */
class WildsFuzzTest {

    private static final int STEPS = 5_000;
    private static final int SEED_PREFIX_STEPS = 2;
    private static final int CLONE_AT = 2_500;
    private static final ActorId GHOST_ACTOR = new ActorId(9, 1);
    private static final OwnerId GHOST_OWNER = new OwnerId(9, 1);

    /** Replayable fuzz op (pure data, resolvable against any equal world view). */
    private sealed interface Op permits Register, Enqueue, Replace, Cancel, SetPolicy,
            AdvanceTo, AdvanceNext {
    }

    private record Register(long seat, String name, String label) implements Op {
    }

    private record Enqueue(int ownerSlot, int actorSlot, int revisionVariant,
                           List<TaskSpec> tasks) implements Op {
    }

    private record Replace(int ownerSlot, int actorSlot, int revisionVariant,
                           List<TaskSpec> tasks) implements Op {
    }

    private record Cancel(int ownerSlot, int actorSlot, int revisionVariant,
                          boolean cancelActive) implements Op {
    }

    private record SetPolicy(int ownerSlot, int actorSlot, int revisionVariant,
                             QueueFailurePolicy policy) implements Op {
    }

    private record AdvanceTo(long deltaMs) implements Op {
    }

    private record AdvanceNext() implements Op {
    }

    @Test
    void fuzzedCommandStreamStaysDeterministicAndInbounded() {
        List<Op> ops = generateOps(new WorldSeed(20_260_404L).stream("fuzz", 0, 0));
        assertEquals(STEPS, ops.size(), "generator must produce the full stream");

        WildsEngine engineA = new WildsEngine(new WorldSeed(1L));
        WildsEngine engineB = new WildsEngine(new WorldSeed(1L));
        WildsEngine engineClone = null;
        Set<ErrorCode> randomRejections = new TreeSet<>();
        long lastNowA = 0L;
        long expectedCursor = 1L;

        for (int step = 0; step < STEPS; step++) {
            Op op = ops.get(step);
            Set<ErrorCode> sink = step >= SEED_PREFIX_STEPS ? randomRejections : null;

            applyOp(op, engineA, sink);
            applyOp(op, engineB, null);

            assertTrue(engineA.nowMs() >= lastNowA, "clock must never move backward");
            assertEquals(engineA.nowMs(), engineB.nowMs(), "clock drift at step " + step);
            lastNowA = engineA.nowMs();

            List<WorldEvent> eventsA = engineA.drainEvents();
            List<WorldEvent> eventsB = engineB.drainEvents();
            assertEquals(eventsA, eventsB, "event stream diverged at step " + step);
            for (WorldEvent event : eventsA) {
                assertEquals(expectedCursor++, event.cursor(),
                        "non-consecutive event cursor at step " + step);
                if (event instanceof WorldEvent.OwnerRegistered) {
                    assertNotNull(event.publicSummary());
                } else {
                    assertNull(event.publicSummary(),
                            "private event must never carry a publicSummary");
                }
            }

            if (step % 50 == 0) {
                assertEquals(engineA.worldHash(), engineB.worldHash(),
                        "worldHash diverged at step " + step);
                assertFogInvariants(engineA, "engineA@" + step);
            }
            assertInvariants(engineA, "engineA@" + step);

            if (step == CLONE_AT) {
                engineClone = WildsEngine.fromSnapshotJson(engineA.snapshotJson());
                assertEquals(engineA.worldHash(), engineClone.worldHash(),
                        "clone must hash identically at the snapshot point");
            } else if (step > CLONE_AT) {
                applyOp(op, engineClone, null);
                List<WorldEvent> eventsClone = engineClone.drainEvents();
                assertEquals(eventsA, eventsClone,
                        "snapshot clone events diverged at step " + step);
                if (step % 50 == 0) {
                    assertEquals(engineA.worldHash(), engineClone.worldHash(),
                            "snapshot clone hash diverged at step " + step);
                }
                assertInvariants(engineClone, "clone@" + step);
            }
        }

        assertNotNull(engineClone, "clone must exist after step " + CLONE_AT);
        assertEquals(engineA.worldHash(), engineB.worldHash(),
                "two independent engines must finish on the exact same WorldHash");
        assertEquals(engineA.worldHash(), engineClone.worldHash(),
                "mid-run snapshot clone must converge on the continuous engine");
        assertEquals(engineA.snapshotJson(), engineB.snapshotJson());
        assertEquals(engineA.snapshotJson(), engineClone.snapshotJson());
        assertTrue(expectedCursor > 100L, "fuzz stream must emit substantial event volume");

        for (ErrorCode required : List.of(ErrorCode.FORBIDDEN, ErrorCode.STALE_STATE,
                ErrorCode.QUEUE_FULL, ErrorCode.INVALID_ARGUMENT, ErrorCode.DUPLICATE_SEAT,
                ErrorCode.UNKNOWN_TARGET, ErrorCode.EMPTY_TASKS)) {
            assertTrue(randomRejections.contains(required),
                    () -> "random fuzz ops never organically hit " + required);
        }
    }

    // ------------------------------------------------------------------ op generation

    private static List<Op> generateOps(SeededRng rng) {
        List<Op> ops = new ArrayList<>(STEPS);

        // Minimal 2-owner bootstrap only; all rejection families must be hit organically!
        ops.add(new Register(1L, "Ash", "Ash's Scout"));
        ops.add(new Register(2L, "Bram", "Bram's Walker"));

        while (ops.size() < STEPS) {
            int roll = rng.nextInt(100);
            if (roll < 6) {
                boolean blankName = rng.nextInt(10) == 0;
                ops.add(new Register(rng.nextRange(1, 4),
                        blankName ? " " : "Fuzz" + rng.nextInt(1_000),
                        "FuzzActor" + rng.nextInt(1_000)));
            } else if (roll < 42) {
                ops.add(new Enqueue(ownerSlot(rng), actorSlot(rng), revisionVariant(rng),
                        randomTasks(rng)));
            } else if (roll < 54) {
                ops.add(new Replace(ownerSlot(rng), actorSlot(rng), revisionVariant(rng),
                        randomTasks(rng)));
            } else if (roll < 64) {
                ops.add(new Cancel(ownerSlot(rng), actorSlot(rng), revisionVariant(rng),
                        rng.nextInt(2) == 0));
            } else if (roll < 70) {
                ops.add(new SetPolicy(ownerSlot(rng), actorSlot(rng), revisionVariant(rng),
                        rng.nextInt(2) == 0 ? QueueFailurePolicy.ADVANCE
                                : QueueFailurePolicy.HALT_ON_FAIL));
            } else if (roll < 92) {
                ops.add(new AdvanceTo(rng.nextRange(0, 60_000)));
            } else {
                ops.add(new AdvanceNext());
            }
        }
        return List.copyOf(ops);
    }

    private static int ownerSlot(SeededRng rng) {
        int roll = rng.nextInt(10);
        return roll == 0 ? -1 : rng.nextInt(3);
    }

    private static int actorSlot(SeededRng rng) {
        int roll = rng.nextInt(10);
        return roll == 0 ? -1 : rng.nextInt(2);
    }

    private static int revisionVariant(SeededRng rng) {
        int roll = rng.nextInt(10);
        if (roll < 5) {
            return 0; // null
        }
        return roll < 8 ? 1 : 2; // correct : stale
    }

    private static List<TaskSpec> randomTasks(SeededRng rng) {
        int count = rng.nextInt(7); // 0..6: 0 exercises EMPTY_TASKS, 6 exercises QUEUE_FULL
        List<TaskSpec> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (rng.nextInt(10) < 6) {
                int bucket = rng.nextInt(10);
                long durationMs = bucket < 2 ? rng.nextRange(0, 999)
                        : bucket < 9 ? rng.nextRange(1_000, 3_600_000)
                        : rng.nextRange(3_600_001, 4_000_000);
                tasks.add(new TaskSpec.Wait(durationMs, rng.nextRange(1, 12)));
            } else {
                tasks.add(new TaskSpec.Travel(new PlaceId(rng.nextRange(0, 4), 1)));
            }
        }
        return List.copyOf(tasks);
    }

    // ------------------------------------------------------------------ op application

    private static void applyOp(Op op, WildsEngine engine, Set<ErrorCode> rejections) {
        WorldView view = engine.view();
        switch (op) {
            case Register register -> submitCommand(engine, rejections,
                    new WorldCommand.RegisterOwner(register.seat(), register.name(),
                            register.label()));
            case Enqueue enqueue -> {
                OwnerId owner = resolveOwner(view, enqueue.ownerSlot());
                ActorId actor = resolveActor(view, enqueue.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                submitCommand(engine, rejections, new WorldCommand.EnqueueTasks(owner, actor,
                        revision(view, actor, enqueue.revisionVariant()), enqueue.tasks()));
            }
            case Replace replace -> {
                OwnerId owner = resolveOwner(view, replace.ownerSlot());
                ActorId actor = resolveActor(view, replace.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                submitCommand(engine, rejections, new WorldCommand.ReplacePendingQueue(owner,
                        actor, revision(view, actor, replace.revisionVariant()),
                        replace.tasks()));
            }
            case Cancel cancel -> {
                OwnerId owner = resolveOwner(view, cancel.ownerSlot());
                ActorId actor = resolveActor(view, cancel.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                submitCommand(engine, rejections, new WorldCommand.CancelQueue(owner, actor,
                        revision(view, actor, cancel.revisionVariant()),
                        cancel.cancelActive()));
            }
            case SetPolicy setPolicy -> {
                OwnerId owner = resolveOwner(view, setPolicy.ownerSlot());
                ActorId actor = resolveActor(view, setPolicy.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                submitCommand(engine, rejections, new WorldCommand.SetFailurePolicy(owner, actor,
                        revision(view, actor, setPolicy.revisionVariant()),
                        setPolicy.policy()));
            }
            case AdvanceTo advanceTo -> engine.advanceTo(engine.nowMs() + advanceTo.deltaMs());
            case AdvanceNext advanceNext -> {
                if (engine.peekNextDueMs().isPresent()) {
                    engine.advanceToNextEvent();
                }
            }
        }
    }

    private static void submitCommand(WildsEngine engine, Set<ErrorCode> rejections,
                                      WorldCommand command) {
        WorldCommand roundTripped = CommandCodec.decode(CommandCodec.encode(command));
        assertEquals(command, roundTripped, "CommandCodec round-trip mismatch");

        long hashBefore = engine.worldHash();
        Phase1Result result = engine.apply(roundTripped);
        if (result instanceof Phase1Result.Rejected rejected) {
            assertEquals(hashBefore, engine.worldHash(),
                    "rejected command mutated WorldState: " + rejected);
            assertTrue(engine.drainEvents().isEmpty(),
                    "rejected command emitted events: " + rejected);
            if (rejections != null) {
                rejections.add(rejected.errorCode());
            }
        }
    }

    private static OwnerId resolveOwner(WorldView view, int slot) {
        if (slot < 0) {
            return GHOST_OWNER;
        }
        List<OwnerRow> owners = view.owners();
        return slot < owners.size() ? owners.get(slot).id() : null;
    }

    private static ActorId resolveActor(WorldView view, int slot) {
        if (slot < 0) {
            return GHOST_ACTOR;
        }
        List<ActorRow> actors = view.actors();
        return slot < actors.size() ? actors.get(slot).id() : null;
    }

    private static Long revision(WorldView view, ActorId actor, int variant) {
        if (variant == 0) {
            return null;
        }
        ActorRow row = view.actor(actor).orElse(null);
        if (row == null) {
            return null;
        }
        return variant == 1 ? row.revision() : row.revision() + 99;
    }

    private static void assertInvariants(WildsEngine engine, String at) {
        WorldView view = engine.view();
        for (ActorRow actor : view.actors()) {
            assertTrue(actor.pendingQueue().size() <= ActorRow.MAX_PENDING_QUEUE,
                    "pending queue overflow at " + at + ": "
                            + actor.pendingQueue().size());
            long energy = actor.energyMilli().valueMilliAt(view.nowMs());
            assertTrue(energy >= 0 && energy <= 100_000,
                    "energy out of bounds at " + at + ": " + energy);
        }
    }

    private static void assertFogInvariants(WildsEngine engine, String at) {
        WorldView view = engine.view();
        for (OwnerRow owner : view.owners()) {
            OwnerObservation obs = ObservationProjector.projectOwner(view, owner.id(),
                    StubDescriptionComposer.INSTANCE);
            Set<PlaceId> discovered = new TreeSet<>(owner.discoveredPlaces());
            assertEquals(view.actorsOwnedBy(owner.id()).size(), obs.actors().size(),
                    "foreign actor leaked at " + at);
            for (PlaceDto place : obs.places()) {
                assertTrue(discovered.contains(place.id()),
                        "undiscovered place leaked at " + at);
                for (PlaceDto.EdgeDto edge : place.edges()) {
                    assertTrue(discovered.contains(edge.to()),
                            "undiscovered edge target leaked at " + at);
                }
            }
        }
    }
}
