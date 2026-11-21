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
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.util.Optional;

/**
 * Strategy seam for the two-phase execution of one {@link TaskSpec} kind.
 *
 * <p><strong>Two-phase contract.</strong> {@link #validatePhase1} is 100% pure and
 * mirrors the per-task checks of the {@code Phase1Validator}. {@link #startPhase2}
 * runs only for the queue-head task and owns the actor-row replacement for the start:
 * it bumps {@code revision}, re-anchors the energy gauge, installs the
 * {@link ActiveTask}, schedules the heap entry, and emits {@code TaskStarted} —
 * returning {@code false} on a Phase-2 failure (e.g. {@code NO_ROUTE}), in which case
 * it must have already emitted {@code TaskFailed} and left the queue untouched (the
 * {@link TaskQueueRunner} owns queue mutation). {@link #onBatchComplete} handles one
 * completed batch; {@link #onCancel} hooks cancellation (the runner centralizes the
 * idle-rate re-anchor and revision handling).
 *
 * <p>Implementations are stateless singletons; all interaction goes through the
 * {@link TaskContext}.
 */
public interface TaskMechanic<P extends TaskSpec> {

    /**
     * Pure Phase-1 structural check; {@code Optional.empty()} means acceptable. Never
     * mutates {@code state}.
     */
    Optional<ErrorCode> validatePhase1(WorldState state, RuleRegistry rules, P spec);

    /**
     * Starts the task for its first batch. Returns {@code true} on success; returns
     * {@code false} after emitting {@code TaskFailed} (and bumping the actor revision)
     * so the runner can apply the queue failure policy.
     */
    boolean startPhase2(TaskContext ctx, ActorRow actor, P spec);

    /** Handles one completed batch of the running {@code task}. */
    void onBatchComplete(TaskContext ctx, ActorRow actor, ActiveTask task);

    /** Hook for task-specific cleanup on cancellation; may be a documented no-op. */
    void onCancel(TaskContext ctx, ActorRow actor, ActiveTask task);
}
