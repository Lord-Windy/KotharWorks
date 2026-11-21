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

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.sim.DuePayload;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.util.List;

/**
 * Deterministic two-phase task queue runner: owns all pending-queue mutation and the
 * idle-rate re-anchor, and delegates per-task mechanics to the stock
 * {@link WaitMechanic}/{@link TravelMechanic} strategies.
 *
 * <p><strong>Fail-soft advancement.</strong> When a task fails Phase 2 (mechanic returns
 * {@code false}), {@code ADVANCE} iteratively attempts the next pending task, while
 * {@code HALT_ON_FAIL} clears the pending queue and stands the actor idle.
 *
 * <p><strong>O(1) cancellation.</strong> Cancelling the active task clears it and lets
 * its stale heap entry pop later: at pop time
 * {@code ev.cancellationToken() != activeTask.heapTicket()} skips the dead event with a
 * single comparison — no heap search or removal.
 */
public final class TaskQueueRunner {

    /** Energy regeneration while standing {@code IDLE_PATIENT} (milli per second). */
    public static final long IDLE_RATE_MILLI_PER_SEC = 25L;

    private TaskQueueRunner() {
    }

    /**
     * Repeatedly pops and starts the queue head while the actor is idle; applies the
     * failure policy on Phase-2 failures, and re-anchors the energy gauge to the idle
     * {@code +25 milli/sec} rate when the actor ends up idle while drifting at another
     * rate (no revision bump — the anchor change alone keeps the hash stable and free
     * of spurious edits).
     */
    public static void startNextTasks(TaskContext ctx, ActorId actorId) {
        while (true) {
            ActorRow row = ctx.state().actors().getOrNull(actorId);
            if (row == null || row.activeTask() != null) {
                return;
            }
            if (row.pendingQueue().isEmpty()) {
                reanchorToIdleRate(ctx, row);
                return;
            }
            TaskSpec head = row.pendingQueue().get(0);
            List<TaskSpec> rest = row.pendingQueue().subList(1, row.pendingQueue().size());
            ctx.state().actors().replace(new ActorRow(row.id(), row.ownerId(), row.label(),
                    row.revision(), row.placeId(), row.energyMilli(), null, rest,
                    row.failPolicy()));
            ActorRow headless = ctx.state().actors().getOrNull(actorId);
            if (headless == null) {
                return;
            }
            boolean started = switch (head) {
                case TaskSpec.Wait waitSpec -> WaitMechanic.INSTANCE.startPhase2(ctx, headless,
                        waitSpec);
                case TaskSpec.Travel travelSpec -> TravelMechanic.INSTANCE.startPhase2(ctx,
                        headless, travelSpec);
            };
            if (started) {
                continue;
            }
            ActorRow failed = ctx.state().actors().getOrNull(actorId);
            if (failed == null) {
                return;
            }
            if (failed.failPolicy() == QueueFailurePolicy.HALT_ON_FAIL) {
                ctx.state().actors().replace(new ActorRow(failed.id(), failed.ownerId(),
                        failed.label(), failed.revision() + 1, failed.placeId(),
                        failed.energyMilli(), null, List.of(), failed.failPolicy()));
                ActorRow halted = ctx.state().actors().getOrNull(actorId);
                if (halted == null) {
                    return;
                }
                reanchorToIdleRate(ctx, halted);
                return;
            }
            // ADVANCE: the failed head is already popped; loop attempts the next task.
        }
    }

    /**
     * Handles one popped heap event. The stale-cancellation guard is a single O(1)
     * token comparison; a matching event dispatches the owning mechanic's batch
     * completion, then advances the queue if the actor went idle.
     */
    public static void onHeapEvent(TaskContext ctx, ScheduledEvent event) {
        switch (event.payload()) {
            case DuePayload.CompleteActorTask(ActorId actorId) -> {
                ActorRow row = ctx.state().actors().getOrNull(actorId);
                if (row == null) {
                    return;
                }
                ActiveTask active = row.activeTask();
                if (active == null || active.heapTicket() != event.cancellationToken()) {
                    // Stale entry from a cancelled task: skip in O(1), mutate nothing.
                    return;
                }
                switch (active.spec()) {
                    case TaskSpec.Wait waitSpec ->
                            WaitMechanic.INSTANCE.onBatchComplete(ctx, row, active);
                    case TaskSpec.Travel travelSpec ->
                            TravelMechanic.INSTANCE.onBatchComplete(ctx, row, active);
                }
                ActorRow after = ctx.state().actors().getOrNull(actorId);
                if (after != null && after.activeTask() == null) {
                    startNextTasks(ctx, actorId);
                }
            }
            case DuePayload.SystemPulse(RuleId pulseId) -> {
                // Documented future hook for world-level periodic events; no-op today.
            }
        }
    }

    /**
     * Cancels the actor's active task: clears it, restores the idle energy rate
     * (bumping the revision), and returns the cancelled task so the caller can emit
     * {@code TaskCancelled}. Its heap entry is skipped O(1) via the token mismatch when
     * it later pops. Returns {@code null} when the actor has no active task.
     */
    public static ActiveTask cancelActive(TaskContext ctx, ActorRow actor) {
        ActiveTask task = actor.activeTask();
        if (task == null) {
            return null;
        }
        switch (task.spec()) {
            case TaskSpec.Wait waitSpec -> WaitMechanic.INSTANCE.onCancel(ctx, actor, task);
            case TaskSpec.Travel travelSpec ->
                    TravelMechanic.INSTANCE.onCancel(ctx, actor, task);
        }
        LazyGauge energy = actor.energyMilli();
        if (energy.rateMilliPerSec() != IDLE_RATE_MILLI_PER_SEC) {
            energy = energy.reanchor(ctx.state().nowMs(), 0L, IDLE_RATE_MILLI_PER_SEC);
        }
        ctx.state().actors().replace(new ActorRow(actor.id(), actor.ownerId(), actor.label(),
                actor.revision() + 1, actor.placeId(), energy, null, actor.pendingQueue(),
                actor.failPolicy()));
        return task;
    }

    private static void reanchorToIdleRate(TaskContext ctx, ActorRow row) {
        if (row.energyMilli().rateMilliPerSec() != IDLE_RATE_MILLI_PER_SEC) {
            WorldState state = ctx.state();
            state.actors().replace(new ActorRow(row.id(), row.ownerId(), row.label(),
                    row.revision(), row.placeId(),
                    row.energyMilli().reanchor(state.nowMs(), 0L, IDLE_RATE_MILLI_PER_SEC),
                    null, row.pendingQueue(), row.failPolicy()));
        }
    }
}
