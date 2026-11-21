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

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.observe.WorldView;

/**
 * Procgen strategy seam: composes the deterministic inspection text for a place or
 * actor at a requested zoom level.
 *
 * <p><strong>Procgen adapter contract.</strong> A future {@code libs/procgen} module
 * will supply its own {@code DescriptionComposer} (prose grammars, biome-flavored
 * vocabulary, name generators). The host application ({@code WildsEngine}/
 * {@code ObservationProjector}, Ticket 04) injects the chosen implementation at
 * observation time; nothing in {@code com.kotharworks.wilds.state}, the command
 * plumbing, or the replay codecs changes — the projector only ever calls this single
 * method to render observations, exclusively through deeply immutable
 * {@link WorldView} snapshots so undiscovered rows can never leak into prose.
 * Strategy seam, not state: no composer is ever stored inside {@code WorldState}.
 *
 * <p>Implementations must be deterministic functions of ({@code view}, {@code target},
 * {@code zoom}) — no wall clock, no unseeded randomness.
 */
@FunctionalInterface
public interface DescriptionComposer {

    /** Detail level of the composed description. */
    enum ZoomLevel { GLANCE, LOOK, EXAMINE }

    /** Closed family of inspectable targets; exhaustively switchable. */
    sealed interface InspectTarget permits InspectTarget.PlaceTarget, InspectTarget.ActorTarget {
        /** Inspect a place row. */
        record PlaceTarget(PlaceId placeId) implements InspectTarget {
        }

        /** Inspect an actor row. */
        record ActorTarget(ActorId actorId) implements InspectTarget {
        }
    }

    /** Composes the description of {@code target} within {@code view} at {@code zoom}. */
    String compose(WorldView view, InspectTarget target, ZoomLevel zoom);
}
