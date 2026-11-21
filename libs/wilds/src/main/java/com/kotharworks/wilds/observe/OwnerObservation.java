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

import java.util.List;
import java.util.Objects;

/**
 * The complete fog-of-war observation of one owner: their own row, their own actors
 * (never other owners'), and only their discovered places with fog-filtered edges.
 * Deeply immutable — safe to hand to any client thread.
 */
public record OwnerObservation(OwnerDto owner, List<ActorDto> actors, List<PlaceDto> places) {

    public OwnerObservation {
        Objects.requireNonNull(owner, "owner");
        actors = List.copyOf(actors);
        places = List.copyOf(places);
    }
}
