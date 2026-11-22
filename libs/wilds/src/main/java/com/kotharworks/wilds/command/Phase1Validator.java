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
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;
import com.kotharworks.wilds.task.TravelMechanic;
import com.kotharworks.wilds.task.WaitMechanic;

import java.util.List;
import java.util.Objects;

/**
 * 100% side-effect-free Phase-1 command validation: reads {@link WorldState} and the
 * {@link RuleRegistry} but never mutates either, never allocates event seqs, and never
 * allocates event cursors — a rejection leaves the world byte-identical
 * ({@code WorldHash}, {@code nextEventSeq}, and {@code nextEventCursor} unchanged).
 *
 * <p>Rejection precedence is fixed and deterministic (first failure wins):
 * owner-argument blanks before seat duplication; actor existence before ownership
 * before revision staleness before queue-cap checks before per-task structural checks.
 * Travel destinations are checked for table existence only — <em>never</em> for
 * adjacency — so optimistic task chains such as {@code [Travel(B), Travel(C)]} can be
 * queued from a non-adjacent place; routing is a Phase-2 concern ({@code NO_ROUTE}).
 */
public final class Phase1Validator {

    private static final Phase1Result.Accepted ACCEPTED = new Phase1Result.Accepted();

    private Phase1Validator() {
    }

    /** Validates {@code command} against {@code state} without any observable effect. */
    public static Phase1Result validate(WorldState state, RuleRegistry rules,
                                        WorldCommand command) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(command, "command");
        return switch (command) {
            case WorldCommand.RegisterOwner registerOwner ->
                    validateRegisterOwner(state, registerOwner);
            case WorldCommand.EnqueueTasks enqueueTasks -> validateActorCommand(state, rules,
                    enqueueTasks.actorOwner(), enqueueTasks.targetActor(),
                    enqueueTasks.expectedRevision(), enqueueTasks.tasks(), true);
            case WorldCommand.ReplacePendingQueue replacePendingQueue ->
                    validateActorCommand(state, rules, replacePendingQueue.actorOwner(),
                            replacePendingQueue.targetActor(),
                            replacePendingQueue.expectedRevision(),
                            replacePendingQueue.tasks(), false);
            case WorldCommand.CancelQueue cancelQueue -> validateActorCommand(state, rules,
                    cancelQueue.actorOwner(), cancelQueue.targetActor(),
                    cancelQueue.expectedRevision(), null, false);
            case WorldCommand.SetFailurePolicy setFailurePolicy -> validateActorCommand(state,
                    rules, setFailurePolicy.actorOwner(), setFailurePolicy.targetActor(),
                    setFailurePolicy.expectedRevision(), null, false);
        };
    }

    private static Phase1Result validateRegisterOwner(WorldState state,
                                                      WorldCommand.RegisterOwner command) {
        if (!isValidNonBlankText(command.ownerName())
                || !isValidNonBlankText(command.initialActorLabel())) {
            return reject(ErrorCode.INVALID_ARGUMENT, 0L,
                    "ownerName and initialActorLabel must be non-blank well-formed UTF-16 without NUL");
        }
        if (state.places().getOrNull(WildsEngine.HOME_PLACE_ID) == null) {
            return reject(ErrorCode.UNKNOWN_TARGET, 0L,
                    "no live home place " + WildsEngine.HOME_PLACE_ID.toWire());
        }
        if (state.ownersBySeatKey().containsKey(command.externalSeatKey())) {
            return reject(ErrorCode.DUPLICATE_SEAT, 0L,
                    "externalSeatKey " + command.externalSeatKey() + " is already registered");
        }
        return ACCEPTED;
    }

    private static boolean isValidNonBlankText(String text) {
        if (text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\0') {
                return false;
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Shared actor-command path. Queue-carrying commands pass their {@code tasks} list
     * ({@code appendToQueue} selects the EnqueueTasks cap rule over the
     * ReplacePendingQueue cap rule); the other actor commands pass {@code null} and skip
     * the task checks entirely.
     */
    private static Phase1Result validateActorCommand(WorldState state, RuleRegistry rules,
                                                     OwnerId actorOwner, ActorId targetActor,
                                                     Long expectedRevision, List<TaskSpec> tasks,
                                                     boolean appendToQueue) {
        OwnerRow ownerRow = state.owners().getOrNull(actorOwner);
        ActorRow actorRow = state.actors().getOrNull(targetActor);
        if (ownerRow == null || actorRow == null) {
            return reject(ErrorCode.UNKNOWN_TARGET, 0L,
                    "no live owner " + actorOwner.toWire() + " or actor " + targetActor.toWire());
        }
        if (!actorRow.ownerId().equals(actorOwner)) {
            return reject(ErrorCode.FORBIDDEN, 0L,
                    "actor " + targetActor.toWire() + " is not owned by " + actorOwner.toWire());
        }
        if (expectedRevision != null && expectedRevision != actorRow.revision()) {
            return reject(ErrorCode.STALE_STATE, actorRow.revision(),
                    "expected revision " + expectedRevision + " but actor "
                            + targetActor.toWire() + " is at revision " + actorRow.revision());
        }
        if (tasks == null) {
            return ACCEPTED;
        }
        if (tasks.isEmpty()) {
            return reject(ErrorCode.EMPTY_TASKS, actorRow.revision(),
                    "task list must not be empty");
        }
        if (appendToQueue) {
            int combined = actorRow.pendingQueue().size() + tasks.size();
            if (combined > ActorRow.MAX_PENDING_QUEUE) {
                return reject(ErrorCode.QUEUE_FULL, actorRow.revision(),
                        "queue cap " + ActorRow.MAX_PENDING_QUEUE + " exceeded: " + combined);
            }
        } else {
            // ReplacePendingQueue caps on the replacement alone, ignoring current pending.
            if (tasks.size() > ActorRow.MAX_PENDING_QUEUE) {
                return reject(ErrorCode.QUEUE_FULL, actorRow.revision(),
                        "queue cap " + ActorRow.MAX_PENDING_QUEUE + " exceeded: "
                                + tasks.size());
            }
        }
        for (TaskSpec spec : tasks) {
            Phase1Result.Rejected rejection = validateSpec(state, rules, spec, actorRow.revision());
            if (rejection != null) {
                return rejection;
            }
        }
        return ACCEPTED;
    }

    /** Delegates per-task structural checks to the owning {@code TaskMechanic} seam. */
    private static Phase1Result.Rejected validateSpec(WorldState state, RuleRegistry rules,
                                                      TaskSpec spec, long currentRevision) {
        return switch (spec) {
            case TaskSpec.Wait waitSpec -> WaitMechanic.INSTANCE
                    .validatePhase1(state, rules, waitSpec)
                    .map(code -> reject(code, currentRevision,
                            describeWaitRejection(code, waitSpec, rules)))
                    .orElse(null);
            case TaskSpec.Travel travelSpec -> TravelMechanic.INSTANCE
                    .validatePhase1(state, rules, travelSpec)
                    .map(code -> reject(code, currentRevision,
                            describeTravelRejection(code, travelSpec.destination())))
                    .orElse(null);
        };
    }

    private static String describeWaitRejection(ErrorCode code, TaskSpec.Wait spec,
                                                RuleRegistry rules) {
        if (code == ErrorCode.UNKNOWN_RULE) {
            return "no \"wait\" task kind in rules";
        }
        RuleRegistry.TaskKindDef kind = rules.taskKinds().get(new RuleId("wait"));
        if (kind != null && (spec.durationMs() < kind.minDurationMs()
                || spec.durationMs() > kind.maxDurationMs())) {
            return "wait durationMs " + spec.durationMs() + " outside ["
                    + kind.minDurationMs() + ", " + kind.maxDurationMs() + "]";
        }
        if (kind != null) {
            return "wait batches " + spec.batches() + " outside [1, " + kind.maxBatches() + "]";
        }
        return "invalid wait task";
    }

    private static String describeTravelRejection(ErrorCode code, PlaceId destination) {
        if (code == ErrorCode.UNKNOWN_RULE) {
            return "no \"travel\" task kind in rules";
        }
        return "no live place " + destination.toWire();
    }

    private static Phase1Result.Rejected reject(ErrorCode errorCode, long currentRevision,
                                                String detail) {
        return new Phase1Result.Rejected(errorCode, currentRevision, detail);
    }
}
