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

import com.kotharworks.wilds.id.PlaceId;

import java.util.List;
import java.util.Objects;

/**
 * Fogged observation of one discovered place. {@code edges} is already filtered to
 * destinations the viewer has discovered — undiscovered neighbor ids never leak.
 */
public record PlaceDto(PlaceId id, String kind, String label, List<EdgeDto> edges) {

    public PlaceDto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        edges = List.copyOf(edges);
    }

    /** One fog-visible outgoing edge of a place. */
    public record EdgeDto(PlaceId to, long travelDurationMs) {

        public EdgeDto {
            Objects.requireNonNull(to, "to");
        }
    }
}
