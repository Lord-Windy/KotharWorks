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
import com.kotharworks.wilds.command.Phase1Validator;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.EventOutbox;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.SnapshotCodec;
import com.kotharworks.wilds.replay.WorldHash;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.sim.DueEventHeap;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;
import com.kotharworks.wilds.task.TaskContext;
import com.kotharworks.wilds.task.TaskQueueRunner;
import com.kotharworks.wilds.worldgen.MinimalSeedTopologyBootstrap;
import com.kotharworks.wilds.worldgen.WorldTopologyBootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeSet;

/**
 * The deterministic, pure headless facade of the Ironwood Wilds simulation: command
 * validation/execution, due-event clock advancement, outward-only events, immutable
 * fog-of-war views, and canonical snapshot round-trips. Pure Java 25: zero I/O, zero
 * wall-clock calls, zero floats — logical time and the seeded world RNG drive
 * everything.
 *
 * <p><strong>Single-writer discipline.</strong> The engine is not thread-safe by design;
 * the host serializes writers (one virtual thread at a time) while any number of
 * readers consume the deeply immutable {@link WorldView} snapshots lock-free.
 *
 * <p>{@link #apply} validates first (Phase 1, 100% side-effect-free: a rejection leaves
 * the world hash and both counters untouched) and executes only accepted commands;
 * {@link #advanceTo} pops due heap events through the {@link TaskQueueRunner}. Neither
 * auto-advances the clock — driving logical time is the host's concern.
 */
public final class WildsEngine {

    /** Stable public API version of this facade. */
    public static final int API_VERSION = 1;

    /** Wire id of the registration home place every new owner starts at. */
    public static final PlaceId HOME_PLACE_ID = new PlaceId(0, 1);

    private final WorldState state;
    private final RuleRegistry rules;
    private final EventOutbox outbox;

    /** Builds an engine over {@code seed} with the minimal default rules and topology. */
    public WildsEngine(WorldSeed seed) {
        this(seed, RuleRegistry.minimalDefault(), MinimalSeedTopologyBootstrap.INSTANCE);
    }

    /**
     * Builds an engine over {@code seed} with an injected rule catalog and topology
     * bootstrap (the procgen adapter seam). The bootstrap must leave a live
     * {@code plc:0:1} home place for owner registration.
     */
    public WildsEngine(WorldSeed seed, RuleRegistry rules, WorldTopologyBootstrap bootstrap) {
        Objects.requireNonNull(seed, "seed");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.outbox = new EventOutbox();
        this.state = new WorldState(seed, RuleRegistry.WILDS_RULES_VERSION);
        Objects.requireNonNull(bootstrap, "bootstrap").populateInitialTopology(state, rules);
    }

    private WildsEngine(WorldState restoredState, RuleRegistry rules) {
        this.state = Objects.requireNonNull(restoredState, "restoredState");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.outbox = new EventOutbox();
    }

    /**
     * Restores an engine from a canonical {@code SnapshotCodec} JSON snapshot. The rule
     * catalog is not part of the snapshot, so the minimal default registry is pinned and
     * a mismatched {@code rulesVersion} is rejected.
     *
     * @throws IllegalArgumentException on malformed JSON or an unsupported rules version
     */
    public static WildsEngine fromSnapshotJson(String json) {
        Objects.requireNonNull(json, "json");
        WorldState restoredState = SnapshotCodec.decode(json);
        if (restoredState.rulesVersion() != RuleRegistry.WILDS_RULES_VERSION) {
            throw new IllegalArgumentException("unsupported rules version: "
                    + restoredState.rulesVersion());
        }
        return new WildsEngine(restoredState, RuleRegistry.minimalDefault());
    }

    /**
     * Pure Phase-1 validation: never touches the world, counters, or outbox — callers
     * may probe optimistic revisions freely.
     */
    public Phase1Result validatePhase1(WorldCommand command) {
        return Phase1Validator.validate(state, rules, command);
    }

    /**
     * Validates {@code command} and, only if accepted, executes it deterministically.
     * Rejections leave the world byte-identical. Does not advance the clock.
     */
    public Phase1Result apply(WorldCommand command) {
        Objects.requireNonNull(command, "command");
        Phase1Result phase1 = Phase1Validator.validate(state, rules, command);
        if (phase1 instanceof Phase1Result.Rejected) {
            return phase1;
        }
        execute(command);
        return phase1;
    }

    private void execute(WorldCommand command) {
        switch (command) {
            case WorldCommand.RegisterOwner c -> executeRegisterOwner(c);
            case WorldCommand.EnqueueTasks c -> {
                ActorRow actor = requireActor(c.targetActor());
                List<TaskSpec> merged = new ArrayList<>(actor.pendingQueue());
                merged.addAll(c.tasks());
                state.actors().replace(new ActorRow(actor.id(), actor.ownerId(), actor.label(),
                        actor.revision() + 1, actor.placeId(), actor.energyMilli(),
                        actor.activeTask(), merged, actor.failPolicy()));
                if (actor.activeTask() == null) {
                    TaskQueueRunner.startNextTasks(context(), actor.id());
                }
            }
            case WorldCommand.ReplacePendingQueue c -> {
                ActorRow actor = requireActor(c.targetActor());
                state.actors().replace(new ActorRow(actor.id(), actor.ownerId(), actor.label(),
                        actor.revision() + 1, actor.placeId(), actor.energyMilli(),
                        actor.activeTask(), c.tasks(), actor.failPolicy()));
                if (actor.activeTask() == null) {
                    TaskQueueRunner.startNextTasks(context(), actor.id());
                }
            }
            case WorldCommand.CancelQueue c -> executeCancelQueue(c);
            case WorldCommand.SetFailurePolicy c -> {
                ActorRow actor = requireActor(c.targetActor());
                state.actors().replace(new ActorRow(actor.id(), actor.ownerId(), actor.label(),
                        actor.revision() + 1, actor.placeId(), actor.energyMilli(),
                        actor.activeTask(), actor.pendingQueue(), c.policy()));
            }
        }
    }

    private void executeRegisterOwner(WorldCommand.RegisterOwner command) {
        PlaceRow homeRow = state.places().getOrNull(HOME_PLACE_ID);
        if (homeRow == null) {
            throw new IllegalStateException("home place " + HOME_PLACE_ID.toWire()
                    + " is missing from the initial topology");
        }
        TreeSet<PlaceId> discovered = new TreeSet<>();
        discovered.add(HOME_PLACE_ID);
        for (PlaceEdge edge : homeRow.edges()) {
            discovered.add(edge.to());
        }
        OwnerRow owner = state.owners().insertNew(id -> new OwnerRow(id,
                command.externalSeatKey(), command.ownerName(), 0, HOME_PLACE_ID,
                List.copyOf(discovered)));
        state.ownersBySeatKey().put(command.externalSeatKey(), owner.id());
        ActorRow actor = state.actors().insertNew(id -> new ActorRow(id, owner.id(),
                command.initialActorLabel(), 0, HOME_PLACE_ID,
                new LazyGauge(100_000L, state.nowMs(), TaskQueueRunner.IDLE_RATE_MILLI_PER_SEC,
                        0L, 100_000L),
                null, List.of(), QueueFailurePolicy.ADVANCE));
        outbox.add(new WorldEvent.OwnerRegistered(owner.id(), actor.id(),
                state.allocateEventCursor(), state.nowMs(), owner.id(),
                "Seat '" + command.ownerName() + "' entered the world",
                "Owner " + owner.id().toWire() + " registered with actor "
                        + actor.id().toWire() + " at " + HOME_PLACE_ID.toWire()));
    }

    private void executeCancelQueue(WorldCommand.CancelQueue command) {
        ActorRow actor = requireActor(command.targetActor());
        int pendingCount = actor.pendingQueue().size();
        boolean activeCancelled = false;
        if (command.cancelActive() && actor.activeTask() != null) {
            TaskQueueRunner.cancelActive(context(), actor);
            activeCancelled = true;
        }
        ActorRow current = requireActor(command.targetActor());
        ActiveTask keptTask = activeCancelled ? null : current.activeTask();
        state.actors().replace(new ActorRow(current.id(), current.ownerId(), current.label(),
                current.revision() + 1, current.placeId(), current.energyMilli(), keptTask,
                List.of(), current.failPolicy()));
        outbox.add(new WorldEvent.TaskCancelled(actor.id(), activeCancelled, pendingCount,
                state.allocateEventCursor(), state.nowMs(), actor.ownerId(), null,
                "Actor '" + actor.label() + "' task queue cancelled (active="
                        + activeCancelled + ", cleared=" + pendingCount + ")"));
        if (keptTask == null) {
            TaskQueueRunner.startNextTasks(context(), actor.id());
        }
    }

    /**
     * Advances the logical clock to {@code targetNowMs}, popping and processing every
     * due heap event with {@code dueAtMs <= targetNowMs} in {@code (dueAtMs, seq)} order
     * along the way. Time never moves backward.
     *
     * @throws IllegalStateException when {@code targetNowMs} is before the current clock
     */
    public long advanceTo(long targetNowMs) {
        DueEventHeap heap = state.dueEvents();
        while (!heap.isEmpty() && heap.peek().dueAtMs() <= targetNowMs) {
            ScheduledEvent event = heap.poll();
            state.advanceClockTo(event.dueAtMs());
            TaskQueueRunner.onHeapEvent(context(), event);
        }
        state.advanceClockTo(targetNowMs);
        return state.nowMs();
    }

    /**
     * Advances to the earliest due event's timestamp, processing every event at that
     * timestamp (including any same-timestamp follow-ups).
     *
     * @throws NoSuchElementException when no events are scheduled
     */
    public long advanceToNextEvent() {
        if (state.dueEvents().isEmpty()) {
            throw new NoSuchElementException("no scheduled events");
        }
        return advanceTo(state.dueEvents().peek().dueAtMs());
    }

    /**
     * The earliest due timestamp, or empty when nothing is scheduled. A stale
     * (cancelled) entry still reports its due time — it is skipped O(1) via its
     * dead cancellation token when it pops.
     */
    public OptionalLong peekNextDueMs() {
        DueEventHeap heap = state.dueEvents();
        return heap.isEmpty() ? OptionalLong.empty() : OptionalLong.of(heap.peek().dueAtMs());
    }

    /** Drains and returns every buffered {@link WorldEvent} in emission (cursor) order. */
    public List<WorldEvent> drainEvents() {
        return outbox.drain();
    }

    /**
     * A freshly built, deeply immutable {@link WorldView} of the current world. Cheap
     * enough to call per step; safe for lock-free concurrent readers.
     */
    public WorldView view() {
        return WorldView.of(state);
    }

    /** Canonical 64-bit structural digest of the current world. */
    public long worldHash() {
        return WorldHash.hash(state);
    }

    /** Canonical JSON snapshot of the current world ({@link SnapshotCodec} format). */
    public String snapshotJson() {
        return SnapshotCodec.encode(state);
    }

    /** Current logical time in milliseconds. */
    public long nowMs() {
        return state.nowMs();
    }

    private ActorRow requireActor(ActorId actorId) {
        ActorRow actor = state.actors().getOrNull(actorId);
        if (actor == null) {
            throw new IllegalStateException("accepted command targeted missing actor "
                    + actorId.toWire());
        }
        return actor;
    }

    private TaskContext context() {
        return new TaskContext(state, rules, outbox);
    }
}
