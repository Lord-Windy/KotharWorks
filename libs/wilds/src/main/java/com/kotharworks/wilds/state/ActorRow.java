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
package com.kotharworks.wilds.state;

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.sim.LazyGauge;

import java.util.List;
import java.util.Objects;

/**
 * Neutral actor row: an entity owned by an {@link OwnerRow}, located at a place,
 * with a lazy-drifting energy gauge, an optional active task ({@code null} ==
 * {@code IDLE_PATIENT}), and a bounded pending task queue.
 *
 * <p>{@code revision} increments on every queue edit <em>and</em> on heap task
 * start/finish/fail — it is the optimistic-concurrency token for actor commands.
 */
public record ActorRow(
        ActorId id,
        OwnerId ownerId,
        String label,
        long revision,
        PlaceId placeId,
        LazyGauge energyMilli,
        ActiveTask activeTask,
        List<TaskSpec> pendingQueue,
        QueueFailurePolicy failPolicy) {

    /** Maximum length of {@code pendingQueue}. */
    public static final int MAX_PENDING_QUEUE = 5;

    public ActorRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(energyMilli, "energyMilli");
        Objects.requireNonNull(failPolicy, "failPolicy");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0: " + revision);
        }
        if (pendingQueue.size() > MAX_PENDING_QUEUE) {
            throw new IllegalArgumentException("pendingQueue must hold at most "
                    + MAX_PENDING_QUEUE + " tasks: " + pendingQueue.size());
        }
        pendingQueue = List.copyOf(pendingQueue);
    }
}
