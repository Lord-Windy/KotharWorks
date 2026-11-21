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

import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.WorldState;
import com.kotharworks.wilds.worldgen.DescriptionComposer.InspectTarget;
import com.kotharworks.wilds.worldgen.DescriptionComposer.ZoomLevel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 03 acceptance: identical seeds give an identical 3-place chain
 * {@code plc:0:1 <-> plc:1:1 <-> plc:2:1}, different seeds vary edge durations within
 * bounds, and the stub composer returns distinct deterministic zoom levels.
 */
class BootstrapTopologyTest {

    private static final long MIN_EDGE_MS = 3_000L;
    private static final long MAX_EDGE_MS = 8_000L;

    private static WorldState populated(WorldSeed seed) {
        WorldState state = new WorldState(seed, RuleRegistry.WILDS_RULES_VERSION);
        MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(state, RuleRegistry.minimalDefault());
        return state;
    }

    private static PlaceRow place(WorldState state, int index) {
        PlaceRow row = state.places().getOrNull(new PlaceId(index, 1));
        assertTrue(row != null, "expected live place plc:" + index + ":1");
        return row;
    }

    @Test
    void sameSeedProducesIdenticalTopology() {
        WorldState first = populated(new WorldSeed(1234));
        WorldState second = populated(new WorldSeed(1234));

        assertEquals(3, first.places().liveCount());
        assertEquals(3, first.places().highWaterMark());

        PlaceRow origin = place(first, 0);
        PlaceRow ridge = place(first, 1);
        PlaceRow boundary = place(first, 2);

        assertEquals("Origin Clearing", origin.label());
        assertEquals(new RuleId("camp"), origin.kindId());
        assertEquals("Ridge Trail", ridge.label());
        assertEquals(new RuleId("trail"), ridge.kindId());
        assertEquals("Old Boundary Stone", boundary.label());
        assertEquals(new RuleId("landmark"), boundary.kindId());

        long edge01Ms = origin.edges().get(0).travelDurationMs();
        long edge12Ms = ridge.edges().get(1).travelDurationMs();

        assertEquals(List.of(new PlaceEdge(ridge.id(), edge01Ms)), origin.edges());
        assertEquals(List.of(new PlaceEdge(origin.id(), edge01Ms),
                new PlaceEdge(boundary.id(), edge12Ms)), ridge.edges());
        assertEquals(List.of(new PlaceEdge(ridge.id(), edge12Ms)), boundary.edges());

        assertTrue(edge01Ms >= MIN_EDGE_MS && edge01Ms <= MAX_EDGE_MS,
                "edge01Ms out of bounds: " + edge01Ms);
        assertTrue(edge12Ms >= MIN_EDGE_MS && edge12Ms <= MAX_EDGE_MS,
                "edge12Ms out of bounds: " + edge12Ms);

        assertEquals(origin, place(second, 0));
        assertEquals(ridge, place(second, 1));
        assertEquals(boundary, place(second, 2));
        assertEquals(first.places().snapshotRows(), second.places().snapshotRows());
    }

    @Test
    void differentSeedsVaryDurationsWithinBounds() {
        Set<String> durationPairs = new HashSet<>();
        for (long seedValue = 1; seedValue <= 16; seedValue++) {
            WorldState state = populated(new WorldSeed(seedValue));
            PlaceRow origin = place(state, 0);
            PlaceRow ridge = place(state, 1);
            PlaceRow boundary = place(state, 2);

            long edge01Ms = origin.edges().get(0).travelDurationMs();
            long edge12Ms = ridge.edges().get(1).travelDurationMs();
            assertTrue(edge01Ms >= MIN_EDGE_MS && edge01Ms <= MAX_EDGE_MS,
                    "seed " + seedValue + " edge01Ms out of bounds: " + edge01Ms);
            assertTrue(edge12Ms >= MIN_EDGE_MS && edge12Ms <= MAX_EDGE_MS,
                    "seed " + seedValue + " edge12Ms out of bounds: " + edge12Ms);
            durationPairs.add(edge01Ms + ":" + edge12Ms);

            assertEquals(origin, place(populated(new WorldSeed(seedValue)), 0),
                    "same seed must be deterministic: " + seedValue);
        }
        assertTrue(durationPairs.size() >= 2,
                "expected >= 2 distinct duration pairs across seeds, got " + durationPairs);
    }

    @Test
    void stubComposerDistinctZoomLevels() {
        WorldState state = populated(new WorldSeed(1234));
        PlaceRow origin = place(state, 0);
        OwnerRow owner = state.owners().insertNew(id ->
                new OwnerRow(id, 1L, "Warden", 0, origin.id(), List.of(origin.id())));
        state.ownersBySeatKey().put(1L, owner.id());
        ActorRow actor = state.actors().insertNew(id ->
                new ActorRow(id, owner.id(), "Scout", 0, origin.id(),
                        new LazyGauge(90_000, 0, 0, 0, 100_000),
                        null, List.of(), QueueFailurePolicy.ADVANCE));

        var composer = StubDescriptionComposer.INSTANCE;

        String[] placeZooms = {
                composer.compose(state, new InspectTarget.PlaceTarget(origin.id()), ZoomLevel.GLANCE),
                composer.compose(state, new InspectTarget.PlaceTarget(origin.id()), ZoomLevel.LOOK),
                composer.compose(state, new InspectTarget.PlaceTarget(origin.id()), ZoomLevel.EXAMINE)};
        assertDistinctNonBlank(placeZooms, "place");
        assertEquals(placeZooms[0], composer.compose(
                state, new InspectTarget.PlaceTarget(origin.id()), ZoomLevel.GLANCE));
        assertEquals(placeZooms[2], composer.compose(
                state, new InspectTarget.PlaceTarget(origin.id()), ZoomLevel.EXAMINE));

        String[] actorZooms = {
                composer.compose(state, new InspectTarget.ActorTarget(actor.id()), ZoomLevel.GLANCE),
                composer.compose(state, new InspectTarget.ActorTarget(actor.id()), ZoomLevel.LOOK),
                composer.compose(state, new InspectTarget.ActorTarget(actor.id()), ZoomLevel.EXAMINE)};
        assertDistinctNonBlank(actorZooms, "actor");
        assertEquals(actorZooms[1], composer.compose(
                state, new InspectTarget.ActorTarget(actor.id()), ZoomLevel.LOOK));

        assertNotEquals(placeZooms[0], actorZooms[0], "place and actor descriptions must differ");
    }

    private static void assertDistinctNonBlank(String[] zooms, String subject) {
        for (String zoom : zooms) {
            assertTrue(zoom != null && !zoom.isBlank(), subject + " zoom output must be non-blank");
        }
        assertNotEquals(zooms[0], zooms[1], subject + " GLANCE vs LOOK");
        assertNotEquals(zooms[1], zooms[2], subject + " LOOK vs EXAMINE");
        assertNotEquals(zooms[0], zooms[2], subject + " GLANCE vs EXAMINE");
    }

    @Test
    void bootstrapOnNonEmptyPlacesThrows() {
        WorldState state = populated(new WorldSeed(7));
        assertThrows(IllegalStateException.class,
                () -> MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(
                        state, RuleRegistry.minimalDefault()));
    }

    @Test
    void bootstrapRequiresRegisteredPlaceKinds() {
        WorldState state = new WorldState(new WorldSeed(7), RuleRegistry.WILDS_RULES_VERSION);
        RuleRegistry incomplete = new RuleRegistry(
                Map.of(new RuleId("camp"), new RuleRegistry.PlaceKindDef(
                        new RuleId("camp"), "Camp")),
                Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> MinimalSeedTopologyBootstrap.INSTANCE.populateInitialTopology(state, incomplete));
    }
}
