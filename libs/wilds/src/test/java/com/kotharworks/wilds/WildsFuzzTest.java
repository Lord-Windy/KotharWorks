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
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.seed.SeededRng;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 04 seeded fuzz acceptance: 5,000 random valid/invalid commands and time jumps
 * against two engines running the identical op stream — zero uncaught exceptions, the
 * queue/energy invariants hold after every step, every rejection family is exercised,
 * and a mid-run {@code SnapshotCodec} clone finishes on exactly the continuous engine's
 * {@code WorldHash}.
 */
class WildsFuzzTest {

    private static final int STEPS = 5_000;
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

        WildsEngine engineFull = new WildsEngine(new WorldSeed(1L));
        WildsEngine engineClone = null;
        Set<ErrorCode> rejections = new TreeSet<>();
        long lastNowFull = 0L;
        long lastNowClone = 0L;

        for (int step = 0; step < STEPS; step++) {
            Op op = ops.get(step);
            applyOp(op, engineFull, rejections);
            assertTrue(engineFull.nowMs() >= lastNowFull, "clock must never move backward");
            lastNowFull = engineFull.nowMs();
            assertInvariants(engineFull, "full@" + step);

            if (step == CLONE_AT) {
                engineClone = WildsEngine.fromSnapshotJson(engineFull.snapshotJson());
                assertEquals(engineFull.worldHash(), engineClone.worldHash(),
                        "clone must hash identically at the snapshot point");
            } else if (step > CLONE_AT) {
                applyOp(op, engineClone, rejections);
                assertTrue(engineClone.nowMs() >= lastNowClone,
                        "clone clock must never move backward");
                lastNowClone = engineClone.nowMs();
                assertInvariants(engineClone, "clone@" + step);
            }
        }

        assertTrue(engineClone != null, "clone must exist after step " + CLONE_AT);
        assertEquals(engineFull.worldHash(), engineClone.worldHash(),
                "mid-run snapshot clone must converge on the continuous engine");
        assertEquals(engineFull.snapshotJson(), engineClone.snapshotJson());

        for (ErrorCode required : List.of(ErrorCode.FORBIDDEN, ErrorCode.STALE_STATE,
                ErrorCode.QUEUE_FULL, ErrorCode.INVALID_ARGUMENT, ErrorCode.DUPLICATE_SEAT,
                ErrorCode.UNKNOWN_TARGET, ErrorCode.EMPTY_TASKS)) {
            assertTrue(rejections.contains(required),
                    () -> "fuzz never produced the " + required + " rejection family");
        }
    }

    // ------------------------------------------------------------------ op generation

    private static List<Op> generateOps(SeededRng rng) {
        List<Op> ops = new ArrayList<>(STEPS);

        // Deterministic prefix: guaranteed registration + every rejection family.
        ops.add(new Register(1L, "Ash", "Ash's Scout"));
        ops.add(new Register(2L, "Bram", "Bram's Walker"));
        ops.add(new Register(1L, "Impostor", "Fake")); // DUPLICATE_SEAT
        ops.add(new Register(3L, "  ", "Blank")); // INVALID_ARGUMENT
        ops.add(new Enqueue(0, 0, 1, List.of(new TaskSpec.Wait(2_000, 1)))); // starts a task
        ops.add(new Enqueue(1, 0, 0, List.of(new TaskSpec.Wait(2_000, 1)))); // FORBIDDEN
        ops.add(new Enqueue(0, 0, 2, List.of(new TaskSpec.Wait(2_000, 1)))); // STALE_STATE
        ops.add(new Enqueue(0, 0, 0, List.of(
                new TaskSpec.Wait(2_000, 1), new TaskSpec.Wait(2_001, 1),
                new TaskSpec.Wait(2_002, 1), new TaskSpec.Wait(2_003, 1),
                new TaskSpec.Wait(2_004, 1), new TaskSpec.Wait(2_005, 1)))); // QUEUE_FULL
        ops.add(new Enqueue(0, -1, 0, List.of(new TaskSpec.Wait(2_000, 1)))); // UNKNOWN_TARGET
        ops.add(new Enqueue(0, 0, 1, List.of())); // EMPTY_TASKS

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
            case Register register -> record(rejections, engine.apply(
                    new WorldCommand.RegisterOwner(register.seat(), register.name(),
                            register.label())));
            case Enqueue enqueue -> {
                OwnerId owner = resolveOwner(view, enqueue.ownerSlot());
                ActorId actor = resolveActor(view, enqueue.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                record(rejections, engine.apply(new WorldCommand.EnqueueTasks(owner, actor,
                        revision(view, actor, enqueue.revisionVariant()), enqueue.tasks())));
            }
            case Replace replace -> {
                OwnerId owner = resolveOwner(view, replace.ownerSlot());
                ActorId actor = resolveActor(view, replace.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                record(rejections, engine.apply(new WorldCommand.ReplacePendingQueue(owner,
                        actor, revision(view, actor, replace.revisionVariant()),
                        replace.tasks())));
            }
            case Cancel cancel -> {
                OwnerId owner = resolveOwner(view, cancel.ownerSlot());
                ActorId actor = resolveActor(view, cancel.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                record(rejections, engine.apply(new WorldCommand.CancelQueue(owner, actor,
                        revision(view, actor, cancel.revisionVariant()),
                        cancel.cancelActive())));
            }
            case SetPolicy setPolicy -> {
                OwnerId owner = resolveOwner(view, setPolicy.ownerSlot());
                ActorId actor = resolveActor(view, setPolicy.actorSlot());
                if (owner == null || actor == null) {
                    return;
                }
                record(rejections, engine.apply(new WorldCommand.SetFailurePolicy(owner, actor,
                        revision(view, actor, setPolicy.revisionVariant()),
                        setPolicy.policy())));
            }
            case AdvanceTo advanceTo -> engine.advanceTo(engine.nowMs() + advanceTo.deltaMs());
            case AdvanceNext advanceNext -> {
                if (engine.peekNextDueMs().isPresent()) {
                    engine.advanceToNextEvent();
                }
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

    private static void record(Set<ErrorCode> rejections, Phase1Result result) {
        if (result instanceof Phase1Result.Rejected rejected) {
            rejections.add(rejected.errorCode());
        }
    }

    private static void assertInvariants(WildsEngine engine, String at) {
        WorldView view = engine.view();
        for (ActorRow actor : view.actors()) {
            assertTrue(actor.pendingQueue().size() <= 5,
                    "pending queue overflow at " + at + ": "
                            + actor.pendingQueue().size());
            long energy = actor.energyMilli().valueMilliAt(view.nowMs());
            assertTrue(energy >= 0 && energy <= 100_000,
                    "energy out of bounds at " + at + ": " + energy);
        }
    }
}
