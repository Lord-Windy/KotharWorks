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
package com.kotharworks.wilds.observe;

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import java.util.List;
import java.util.Objects;

/**
 * Fogged observation of one owned actor: revision, live energy value at the view's
 * clock, the active task, the pending queue, the failure policy, and the composed
 * {@code look} text of the place the actor stands on.
 */
public record ActorDto(ActorId id, String label, long revision, PlaceId placeId,
                       long energyMilliAtNow, ActiveTask activeTask, List<TaskSpec> pendingQueue,
                       QueueFailurePolicy failPolicy, String look) {

    public ActorDto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(placeId, "placeId");
        Objects.requireNonNull(pendingQueue, "pendingQueue");
        Objects.requireNonNull(failPolicy, "failPolicy");
        Objects.requireNonNull(look, "look");
        pendingQueue = List.copyOf(pendingQueue);
    }
}
