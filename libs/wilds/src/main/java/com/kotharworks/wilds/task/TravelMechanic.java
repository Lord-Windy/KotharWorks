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

import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.sim.DuePayload;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Travel task mechanic: requires a live {@link PlaceEdge} from the actor's current
 * place to the destination at Phase-2 start (Phase 1 deliberately checks only table
 * existence so optimistic chains can be queued). Drains energy at {@code -40
 * milli/sec} while walking; below {@code 15_000} milli the edge duration is stretched
 * by an integer {@code 1_350} permille ({@code 1.35x}) penalty — zero floats. On
 * arrival the actor moves, the destination and its edge targets join the owner's
 * discovered places, and one {@code PlaceDiscovered} event is emitted per newly
 * revealed place.
 */
public final class TravelMechanic implements TaskMechanic<TaskSpec.Travel> {

    /** Energy drain while a travel leg is running (milli per logical second). */
    public static final long TRAVEL_RATE_MILLI_PER_SEC = -40L;

    /** Energy strictly below which the tired-walker duration penalty kicks in. */
    public static final long LOW_ENERGY_MILLI = 15_000L;

    /** Integer permille duration multiplier applied when energy is low: {@code 1.35x}. */
    public static final long PENALTY_PERMILLE = 1_350L;

    /** Stateless singleton. */
    public static final TravelMechanic INSTANCE = new TravelMechanic();

    private static final RuleId TRAVEL_RULE_ID = new RuleId("travel");

    private TravelMechanic() {
    }

    @Override
    public Optional<ErrorCode> validatePhase1(WorldState state, RuleRegistry rules,
                                              TaskSpec.Travel spec) {
        if (!rules.taskKinds().containsKey(TRAVEL_RULE_ID)) {
            return Optional.of(ErrorCode.UNKNOWN_RULE);
        }
        if (state.places().getOrNull(spec.destination()) == null) {
            return Optional.of(ErrorCode.UNKNOWN_TARGET);
        }
        return Optional.empty();
    }

    @Override
    public boolean startPhase2(TaskContext ctx, ActorRow actor, TaskSpec.Travel spec) {
        long now = ctx.state().nowMs();
        PlaceRow currentPlace = ctx.state().places().getOrNull(actor.placeId());
        PlaceEdge edge = currentPlace == null ? null : edgeTo(currentPlace, spec.destination());
        if (edge == null) {
            // Phase-2 failure: emit TaskFailed(NO_ROUTE), bump revision, leave the queue
            // untouched — the runner applies the queue failure policy.
            ctx.state().actors().replace(new ActorRow(actor.id(), actor.ownerId(),
                    actor.label(), actor.revision() + 1, actor.placeId(), actor.energyMilli(),
                    null, actor.pendingQueue(), actor.failPolicy()));
            ctx.emit(new WorldEvent.TaskFailed(actor.id(), spec, ErrorCode.NO_ROUTE,
                    ctx.state().allocateEventCursor(), now, actor.ownerId(), null,
                    "Actor '" + actor.label() + "' failed travel to "
                            + spec.destination().toWire() + ": no route"));
            return false;
        }
        long energyBefore = actor.energyMilli().valueMilliAt(now);
        LazyGauge energy = actor.energyMilli().reanchor(now, 0L, TRAVEL_RATE_MILLI_PER_SEC);
        long effectiveMs = energyBefore < LOW_ENERGY_MILLI
                ? penalizedDurationMs(edge.travelDurationMs())
                : edge.travelDurationMs();
        long dueAtMs = Math.addExact(now, effectiveMs);
        long ticket = ctx.state().allocateEventSeq();
        ActiveTask task = new ActiveTask(now, dueAtMs, ticket, spec, 1);
        ctx.state().actors().replace(new ActorRow(actor.id(), actor.ownerId(), actor.label(),
                actor.revision() + 1, actor.placeId(), energy, task, actor.pendingQueue(),
                actor.failPolicy()));
        ctx.state().dueEvents().push(new ScheduledEvent(task.dueAtMs(), ticket, ticket,
                new DuePayload.CompleteActorTask(actor.id())));
        ctx.emit(new WorldEvent.TaskStarted(actor.id(), task,
                ctx.state().allocateEventCursor(), now, actor.ownerId(), null,
                "Actor '" + actor.label() + "' started travel to "
                        + spec.destination().toWire()));
        return true;
    }

    @Override
    public void onBatchComplete(TaskContext ctx, ActorRow actor, ActiveTask task) {
        TaskSpec.Travel spec = (TaskSpec.Travel) task.spec();
        long now = ctx.state().nowMs();
        PlaceId destination = spec.destination();
        // Arrival: move the actor and clear the active task (the runner starts the next
        // queued task or re-anchors the idle +25 rate).
        ActorRow arrived = new ActorRow(actor.id(), actor.ownerId(), actor.label(),
                actor.revision() + 1, destination, actor.energyMilli(), null,
                actor.pendingQueue(), actor.failPolicy());
        ctx.state().actors().replace(arrived);
        ctx.emit(new WorldEvent.TaskBatchCompleted(actor.id(), spec, 0,
                ctx.state().allocateEventCursor(), now, actor.ownerId(), null,
                "Actor '" + actor.label() + "' arrived at " + destination.toWire()));
        revealAround(ctx, arrived, destination, now);
    }

    /**
     * Documented no-op: the {@link TaskQueueRunner} centralizes the idle-rate re-anchor
     * and revision handling for cancellations.
     */
    @Override
    public void onCancel(TaskContext ctx, ActorRow actor, ActiveTask task) {
        // no task-specific cleanup required for travels
    }

    /** Adds {@code destination} plus its edge targets to the owner's discovered set. */
    private static void revealAround(TaskContext ctx, ActorRow actor, PlaceId destination,
                                     long now) {
        OwnerRow owner = ctx.state().owners().getOrNull(actor.ownerId());
        PlaceRow destinationRow = ctx.state().places().getOrNull(destination);
        if (owner == null || destinationRow == null) {
            return;
        }
        TreeSet<PlaceId> discovered = new TreeSet<>(owner.discoveredPlaces());
        List<PlaceId> newlyRevealed = new ArrayList<>();
        if (discovered.add(destination)) {
            newlyRevealed.add(destination);
        }
        for (PlaceEdge edge : destinationRow.edges()) {
            if (discovered.add(edge.to())) {
                newlyRevealed.add(edge.to());
            }
        }
        if (newlyRevealed.isEmpty()) {
            return;
        }
        OwnerRow revealed = new OwnerRow(owner.id(), owner.externalSeatKey(), owner.name(),
                owner.revision() + 1, owner.homePlaceId(), List.copyOf(discovered));
        ctx.state().owners().replace(revealed);
        for (PlaceId place : newlyRevealed) {
            ctx.emit(new WorldEvent.PlaceDiscovered(place, actor.id(),
                    ctx.state().allocateEventCursor(), now, owner.id(), null,
                    "Owner '" + owner.name() + "' discovered " + place.toWire()));
        }
    }

    private static PlaceEdge edgeTo(PlaceRow from, PlaceId destination) {
        for (PlaceEdge edge : from.edges()) {
            if (edge.to().equals(destination)) {
                return edge;
            }
        }
        return null;
    }

    private static long penalizedDurationMs(long baseDurationMs) {
        try {
            return Math.multiplyExact(baseDurationMs, PENALTY_PERMILLE) / 1_000L;
        } catch (ArithmeticException overflow) {
            return BigInteger.valueOf(baseDurationMs)
                    .multiply(BigInteger.valueOf(PENALTY_PERMILLE))
                    .divide(BigInteger.valueOf(1_000L))
                    .longValueExact();
        }
    }
}
