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
package com.kotharworks.wilds.event;

import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.TaskSpec;

import java.util.Objects;

/**
 * Closed family of outward-only world events; exhaustively switchable. Every event
 * carries a monotonically increasing {@code cursor} (allocated from
 * {@code WorldState.nextEventCursor} at the emission point), the logical
 * {@code occurredMs}, the {@code scopeOwner} the event belongs to, and two summaries:
 * {@code publicSummary} (nullable; world-wire safe, e.g. public feeds and chronicles)
 * and {@code privateSummary} (non-null; scoped-owner-visible detail).
 *
 * <p>Events are transient engine output — they are never part of {@code WorldState}
 * or the {@code WorldHash}; the cursor counter is, so a checkpoint + replay
 * reproduces identical cursors.
 */
public sealed interface WorldEvent
        permits WorldEvent.OwnerRegistered, WorldEvent.TaskStarted,
        WorldEvent.TaskBatchCompleted, WorldEvent.TaskCancelled, WorldEvent.TaskFailed,
        WorldEvent.PlaceDiscovered {

    /** A new owner seat entered the world (the only event with a public summary). */
    record OwnerRegistered(OwnerId ownerId, ActorId initialActorId, long cursor,
                           long occurredMs, OwnerId scopeOwner, String publicSummary,
                           String privateSummary) implements WorldEvent {

        public OwnerRegistered {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(initialActorId, "initialActorId");
            requireCommonInvariants(cursor, occurredMs, scopeOwner, privateSummary);
            if (publicSummary == null || publicSummary.isBlank()) {
                throw new IllegalArgumentException(
                        "OwnerRegistered requires a non-blank publicSummary");
            }
        }
    }

    /** An actor started (or re-anchored into) a task batch. */
    record TaskStarted(ActorId actorId, ActiveTask task, long cursor, long occurredMs,
                       OwnerId scopeOwner, String publicSummary, String privateSummary)
            implements WorldEvent {

        public TaskStarted {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(task, "task");
            requireCommonInvariants(cursor, occurredMs, scopeOwner, privateSummary);
        }
    }

    /** One task batch finished; {@code batchesLeftAfter} counts the remaining batches. */
    record TaskBatchCompleted(ActorId actorId, TaskSpec spec, int batchesLeftAfter, long cursor,
                              long occurredMs, OwnerId scopeOwner, String publicSummary,
                              String privateSummary) implements WorldEvent {

        public TaskBatchCompleted {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(spec, "spec");
            if (batchesLeftAfter < 0) {
                throw new IllegalArgumentException(
                        "batchesLeftAfter must be >= 0: " + batchesLeftAfter);
            }
            requireCommonInvariants(cursor, occurredMs, scopeOwner, privateSummary);
        }
    }

    /** A queue cancellation happened; {@code clearedPendingCount} is the drained size. */
    record TaskCancelled(ActorId actorId, boolean cancelledActive, int clearedPendingCount,
                         long cursor, long occurredMs, OwnerId scopeOwner, String publicSummary,
                         String privateSummary) implements WorldEvent {

        public TaskCancelled {
            Objects.requireNonNull(actorId, "actorId");
            if (clearedPendingCount < 0) {
                throw new IllegalArgumentException(
                        "clearedPendingCount must be >= 0: " + clearedPendingCount);
            }
            requireCommonInvariants(cursor, occurredMs, scopeOwner, privateSummary);
        }
    }

    /** A task failed in Phase 2 (e.g. {@code NO_ROUTE}); the failure policy then applies. */
    record TaskFailed(ActorId actorId, TaskSpec spec, ErrorCode failureCode, long cursor,
                      long occurredMs, OwnerId scopeOwner, String publicSummary,
                      String privateSummary) implements WorldEvent {

        public TaskFailed {
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(failureCode, "failureCode");
            requireCommonInvariants(cursor, occurredMs, scopeOwner, privateSummary);
        }
    }

    /** A place (or one of its neighbors) became newly visible to the owning owner. */
    record PlaceDiscovered(PlaceId placeId, ActorId discoveredByActor, long cursor,
                           long occurredMs, OwnerId scopeOwner, String publicSummary,
                           String privateSummary) implements WorldEvent {

        public PlaceDiscovered {
            Objects.requireNonNull(placeId, "placeId");
            Objects.requireNonNull(discoveredByActor, "discoveredByActor");
            requireCommonInvariants(cursor, occurredMs, scopeOwner, privateSummary);
        }
    }

    long cursor();

    long occurredMs();

    OwnerId scopeOwner();

    /** Nullable: private events carry {@code null} so public feeds stay world-safe. */
    String publicSummary();

    String privateSummary();

    private static void requireCommonInvariants(long cursor, long occurredMs, OwnerId scopeOwner,
                                                String privateSummary) {
        if (cursor < 1) {
            throw new IllegalArgumentException("cursor must be >= 1: " + cursor);
        }
        if (occurredMs < 0) {
            throw new IllegalArgumentException("occurredMs must be >= 0: " + occurredMs);
        }
        Objects.requireNonNull(scopeOwner, "scopeOwner");
        Objects.requireNonNull(privateSummary, "privateSummary");
    }
}
