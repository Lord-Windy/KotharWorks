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
package com.kotharworks.wilds.worldgen;

import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.SeededRng;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.WorldState;

import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * Deterministic minimal default topology: a 3-node connected chain
 * {@code plc:0:1 <-> plc:1:1 <-> plc:2:1} with symmetric seeded travel durations drawn
 * from {@code state.seed().stream("bootstrap", 0, 0)} in {@code 3_000..8_000 ms}.
 *
 * <p>No direct {@code 0 <-> 2} edge, so multi-hop travel and non-adjacent routing are
 * exercisable from the very first world. A future {@code libs/procgen} adapter replaces
 * this stub wholesale via the {@link WorldTopologyBootstrap} seam; see that interface
 * for the injection contract.
 */
public final class MinimalSeedTopologyBootstrap implements WorldTopologyBootstrap {

    public static final MinimalSeedTopologyBootstrap INSTANCE = new MinimalSeedTopologyBootstrap();

    private static final int MIN_EDGE_MS = 3_000;
    private static final int MAX_EDGE_MS = 8_000;

    @Override
    public void populateInitialTopology(WorldState state, RuleRegistry rules) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rules, "rules");
        if (state.places().highWaterMark() != 0) {
            throw new IllegalStateException(
                    "initial topology requires an uninitialized place table: highWaterMark="
                            + state.places().highWaterMark());
        }
        RuleId campId = requirePlaceKind(rules, "camp");
        RuleId trailId = requirePlaceKind(rules, "trail");
        RuleId landmarkId = requirePlaceKind(rules, "landmark");

        SeededRng rng = state.seed().stream("bootstrap", 0, 0);
        int edge01Ms = rng.nextRange(MIN_EDGE_MS, MAX_EDGE_MS);
        int edge12Ms = rng.nextRange(MIN_EDGE_MS, MAX_EDGE_MS);

        PlaceRow origin = state.places()
                .insertNew(id -> new PlaceRow(id, campId, "Origin Clearing", List.of()));
        PlaceRow ridge = state.places()
                .insertNew(id -> new PlaceRow(id, trailId, "Ridge Trail", List.of()));
        PlaceRow boundary = state.places()
                .insertNew(id -> new PlaceRow(id, landmarkId, "Old Boundary Stone", List.of()));

        state.places().replace(new PlaceRow(origin.id(), campId, origin.label(),
                List.of(new PlaceEdge(ridge.id(), edge01Ms))));
        state.places().replace(new PlaceRow(ridge.id(), trailId, ridge.label(),
                List.of(new PlaceEdge(origin.id(), edge01Ms), new PlaceEdge(boundary.id(), edge12Ms))));
        state.places().replace(new PlaceRow(boundary.id(), landmarkId, boundary.label(),
                List.of(new PlaceEdge(ridge.id(), edge12Ms))));
    }

    private static RuleId requirePlaceKind(RuleRegistry rules, String slug) {
        RuleId kindId = new RuleId(slug);
        SequencedMap<RuleId, RuleRegistry.PlaceKindDef> placeKinds = rules.placeKinds();
        if (!placeKinds.containsKey(kindId)) {
            throw new IllegalArgumentException("rules registry lacks place kind \"" + slug + "\"");
        }
        return kindId;
    }
}
