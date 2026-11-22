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
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.sim.DuePayload;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.util.Optional;

/**
 * Wait task mechanic: always succeeds Phase 2, regenerates energy at
 * {@code +50 milli/sec} while a batch runs, and re-queues the next batch until
 * {@code batchesLeft} reaches zero (the runner then advances the queue, or the actor
 * stands idle at {@code +25 milli/sec}).
 */
public final class WaitMechanic implements TaskMechanic<TaskSpec.Wait> {

    /** Energy regeneration while a wait batch is running (milli per logical second). */
    public static final long WAIT_RATE_MILLI_PER_SEC = 50L;

    /** Stateless singleton. */
    public static final WaitMechanic INSTANCE = new WaitMechanic();

    private static final RuleId WAIT_RULE_ID = new RuleId("wait");

    private WaitMechanic() {
    }

    @Override
    public Optional<ErrorCode> validatePhase1(WorldState state, RuleRegistry rules,
                                              TaskSpec.Wait spec) {
        RuleRegistry.TaskKindDef kind = rules.taskKinds().get(WAIT_RULE_ID);
        if (kind == null) {
            return Optional.of(ErrorCode.UNKNOWN_RULE);
        }
        if (spec.durationMs() < kind.minDurationMs() || spec.durationMs() > kind.maxDurationMs()
                || spec.batches() < 1 || spec.batches() > kind.maxBatches()) {
            return Optional.of(ErrorCode.INVALID_ARGUMENT);
        }
        return Optional.empty();
    }

    @Override
    public boolean startPhase2(TaskContext ctx, ActorRow actor, TaskSpec.Wait spec) {
        long now = ctx.state().nowMs();
        LazyGauge energy = actor.energyMilli().reanchor(now, 0L, WAIT_RATE_MILLI_PER_SEC);
        long ticket = ctx.state().allocateEventSeq();
        ActiveTask task = new ActiveTask(now, Math.addExact(now, spec.durationMs()), ticket, spec,
                spec.batches());
        ctx.state().actors().replace(withActiveTask(actor, energy, task));
        ctx.state().dueEvents().push(new ScheduledEvent(task.dueAtMs(), ticket, ticket,
                new DuePayload.CompleteActorTask(actor.id())));
        ctx.emit(new WorldEvent.TaskStarted(actor.id(), task,
                ctx.state().allocateEventCursor(), now, actor.ownerId(), null,
                "Actor '" + actor.label() + "' started wait " + spec.durationMs()
                        + "ms x" + spec.batches()));
        return true;
    }

    @Override
    public void onBatchComplete(TaskContext ctx, ActorRow actor, ActiveTask task) {
        TaskSpec.Wait spec = (TaskSpec.Wait) task.spec();
        int batchesLeftAfter = task.batchesLeft() - 1;
        long now = ctx.state().nowMs();
        if (batchesLeftAfter > 0) {
            long ticket = ctx.state().allocateEventSeq();
            ActiveTask nextBatch = new ActiveTask(now, Math.addExact(now, spec.durationMs()),
                    ticket, spec, batchesLeftAfter);
            ctx.state().actors().replace(withActiveTask(actor, actor.energyMilli(), nextBatch));
            ctx.state().dueEvents().push(new ScheduledEvent(nextBatch.dueAtMs(), ticket, ticket,
                    new DuePayload.CompleteActorTask(actor.id())));
        } else {
            // Last batch: clear the active task; the runner advances the queue (or re-anchors
            // the idle +25 rate).
            ctx.state().actors().replace(withActiveTask(actor, actor.energyMilli(), null));
        }
        ctx.emit(new WorldEvent.TaskBatchCompleted(actor.id(), spec, batchesLeftAfter,
                ctx.state().allocateEventCursor(), now, actor.ownerId(), null,
                "Actor '" + actor.label() + "' completed a wait batch (" + batchesLeftAfter
                        + " batches left)"));
    }

    /**
     * Documented no-op: the {@link TaskQueueRunner} centralizes the idle-rate re-anchor
     * and revision handling for cancellations. Kept as an explicit seam for future
     * mechanics that need per-task cancellation cleanup.
     */
    @Override
    public void onCancel(TaskContext ctx, ActorRow actor, ActiveTask task) {
        // no task-specific cleanup required for waits
    }

    private static ActorRow withActiveTask(ActorRow actor, LazyGauge energy, ActiveTask task) {
        return new ActorRow(actor.id(), actor.ownerId(), actor.label(), actor.revision() + 1,
                actor.placeId(), energy, task, actor.pendingQueue(), actor.failPolicy());
    }
}
