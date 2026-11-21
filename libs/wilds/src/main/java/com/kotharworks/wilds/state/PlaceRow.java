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
import com.kotharworks.wilds.id.RuleId;

import java.util.List;
import java.util.Objects;

/**
 * Neutral place row: a node of the world graph with a structural kind, a display
 * label, and its outgoing edges in a caller-defined deterministic order.
 */
public record PlaceRow(
        PlaceId id,
        RuleId kindId,
        String label,
        List<PlaceEdge> edges) {

    public PlaceRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kindId, "kindId");
        Objects.requireNonNull(label, "label");
        edges = List.copyOf(edges);
    }
}
