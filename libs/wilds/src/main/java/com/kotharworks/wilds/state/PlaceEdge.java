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

import com.kotharworks.wilds.id.PlaceId;

import java.util.Objects;

/**
 * One directed edge of the place graph: travel to {@code to} takes
 * {@code travelDurationMs} of logical time. Edge list order is caller-defined and
 * deterministic (the topology bootstrap defines it).
 */
public record PlaceEdge(PlaceId to, long travelDurationMs) {

    public PlaceEdge {
        Objects.requireNonNull(to, "to");
        if (travelDurationMs < 0) {
            throw new IllegalArgumentException("travelDurationMs must be >= 0: " + travelDurationMs);
        }
    }
}
