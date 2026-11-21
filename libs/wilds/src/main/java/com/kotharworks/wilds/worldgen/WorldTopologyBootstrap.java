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

import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.state.WorldState;

/**
 * Procgen strategy seam: populates the initial place-graph topology of a fresh
 * {@link WorldState}.
 *
 * <p><strong>Procgen adapter contract.</strong> A future {@code libs/procgen} module will
 * supply its own {@code WorldTopologyBootstrap} implementation (real terrain, biomes,
 * river networks, region packing). The host application ({@code WildsEngine}/
 * {@code WorldHost}, Ticket 04) injects the chosen implementation at construction time;
 * nothing in {@code com.kotharworks.wilds.state}, the command plumbing, or the
 * replay codecs changes — the engine only ever calls this single method. Strategy seam,
 * not state: no bootstrap implementation is ever stored inside {@code WorldState}.
 *
 * <p>Implementations must be deterministic given {@code state.seed()} and may only
 * mutate {@code state} through its public table APIs ({@code insertNew}/{@code replace}).
 */
@FunctionalInterface
public interface WorldTopologyBootstrap {

    /** Populates the initial topology of {@code state}, which must have an empty place table. */
    void populateInitialTopology(WorldState state, RuleRegistry rules);
}
