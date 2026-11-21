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

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import java.util.List;
import java.util.Objects;

/**
 * Closed family of world commands accepted by the {@code WildsEngine}; exhaustively
 * switchable. Every command is a plain immutable value: validation (Phase 1) is 100%
 * side-effect-free, and execution (Phase 2 of the engine, not of tasks) mutates
 * {@code WorldState} only after acceptance.
 */
public sealed interface WorldCommand
        permits WorldCommand.RegisterOwner, WorldCommand.EnqueueTasks,
        WorldCommand.ReplacePendingQueue, WorldCommand.CancelQueue,
        WorldCommand.SetFailurePolicy {

    /**
     * Registers a new owner seat: allocates the {@code OwnerRow} at the home place
     * {@code plc:0:1}, discovers {@code plc:0:1} plus its immediate neighbors, and
     * spawns one initial actor standing idle at home with full energy.
     */
    record RegisterOwner(long externalSeatKey, String ownerName, String initialActorLabel)
            implements WorldCommand {

        public RegisterOwner {
            Objects.requireNonNull(ownerName, "ownerName");
            Objects.requireNonNull(initialActorLabel, "initialActorLabel");
        }
    }

    /**
     * Appends {@code tasks} to the actor's pending queue (cap 5 combined with the
     * current queue). {@code expectedRevision} is the optional optimistic-concurrency
     * token; {@code null} skips the check.
     */
    record EnqueueTasks(OwnerId actorOwner, ActorId targetActor, Long expectedRevision,
                        List<TaskSpec> tasks) implements WorldCommand {

        public EnqueueTasks {
            Objects.requireNonNull(actorOwner, "actorOwner");
            Objects.requireNonNull(targetActor, "targetActor");
            Objects.requireNonNull(tasks, "tasks");
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * Replaces the actor's whole pending queue with {@code tasks} (cap 5 on the
     * replacement alone; it is not added to the current queue). Clearing the queue
     * entirely is expressed via {@link CancelQueue}.
     */
    record ReplacePendingQueue(OwnerId actorOwner, ActorId targetActor, Long expectedRevision,
                               List<TaskSpec> tasks) implements WorldCommand {

        public ReplacePendingQueue {
            Objects.requireNonNull(actorOwner, "actorOwner");
            Objects.requireNonNull(targetActor, "targetActor");
            Objects.requireNonNull(tasks, "tasks");
            tasks = List.copyOf(tasks);
        }
    }

    /**
     * Clears the actor's pending queue; when {@code cancelActive} is true the running
     * task is cancelled too (its heap entry is later skipped in O(1) via the stale
     * {@code heapTicket} token).
     */
    record CancelQueue(OwnerId actorOwner, ActorId targetActor, Long expectedRevision,
                       boolean cancelActive) implements WorldCommand {

        public CancelQueue {
            Objects.requireNonNull(actorOwner, "actorOwner");
            Objects.requireNonNull(targetActor, "targetActor");
        }
    }

    /** Sets the actor's {@link QueueFailurePolicy}. */
    record SetFailurePolicy(OwnerId actorOwner, ActorId targetActor, Long expectedRevision,
                            QueueFailurePolicy policy) implements WorldCommand {

        public SetFailurePolicy {
            Objects.requireNonNull(actorOwner, "actorOwner");
            Objects.requireNonNull(targetActor, "targetActor");
            Objects.requireNonNull(policy, "policy");
        }
    }
}
