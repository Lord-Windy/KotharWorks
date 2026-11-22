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

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.worldgen.DescriptionComposer.InspectTarget;
import com.kotharworks.wilds.worldgen.DescriptionComposer.ZoomLevel;
import com.kotharworks.wilds.worldgen.StubDescriptionComposer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 04 fog-of-war acceptance: undiscovered places and foreign actors/queues never
 * leak through {@link ObservationProjector}, and {@code examine} enforces the same
 * isolation with deterministic error codes.
 */
class ObservationProjectorTest {

    private static final WorldSeed SEED = new WorldSeed(0x5EED0004L);
    private static final StubDescriptionComposer COMPOSER = StubDescriptionComposer.INSTANCE;

    private static WildsEngine twoOwnerEngine() {
        WildsEngine engine = new WildsEngine(SEED);
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.RegisterOwner(1L, "Ash", "Ash's Scout")));
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.RegisterOwner(2L, "Bram", "Bram's Walker")));
        engine.drainEvents();
        return engine;
    }

    private static OwnerId owner(WildsEngine engine, long seat) {
        return engine.view().findOwnerBySeatKey(seat).orElseThrow().id();
    }

    private static ActorId actorOf(WildsEngine engine, OwnerId ownerId) {
        return engine.view().actorsOwnedBy(ownerId).get(0).id();
    }

    @Test
    void undiscoveredNeighborNeverLeaksThroughPlaceEdges() {
        WildsEngine engine = twoOwnerEngine();
        OwnerId bram = owner(engine, 2L);

        OwnerObservation bramView = ObservationProjector.projectOwner(engine.view(), bram,
                COMPOSER);
        assertEquals(bram, bramView.owner().id());
        assertEquals(1, bramView.actors().size(), "owner 2 sees only their own actor");
        List<PlaceId> placeIds = bramView.places().stream().map(PlaceDto::id).toList();
        assertEquals(List.of(new PlaceId(0, 1), new PlaceId(1, 1)), placeIds,
                "a fresh owner sees only the home place and its immediate neighbor");

        PlaceDto ridge = bramView.places().stream()
                .filter(place -> place.id().equals(new PlaceId(1, 1)))
                .findFirst().orElseThrow();
        assertEquals(List.of(new PlaceId(0, 1)),
                ridge.edges().stream().map(PlaceDto.EdgeDto::to).toList(),
                "undiscovered plc:2:1 must be filtered out of plc:1:1's edges");
    }

    @Test
    void fogIsolationOmitsForeignActorsAndUndiscoveredPlaces() {
        WildsEngine engine = twoOwnerEngine();
        OwnerId ash = owner(engine, 1L);
        OwnerId bram = owner(engine, 2L);

        // Owner 1 walks the chain to plc:2:1; owner 2 stays at plc:0:1.
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(ash, actorOf(engine, ash), null,
                        List.of(new TaskSpec.Travel(new PlaceId(1, 1)),
                                new TaskSpec.Travel(new PlaceId(2, 1))))));
        while (engine.peekNextDueMs().isPresent()) {
            engine.advanceToNextEvent();
        }
        engine.drainEvents();

        OwnerObservation bramView = ObservationProjector.projectOwner(engine.view(), bram,
                COMPOSER);
        assertNeverShows(bramView, new PlaceId(2, 1),
                "owner 2's observation must omit the undiscovered plc:2:1");
        assertEquals(1, bramView.actors().size(), "owner 2 must not see owner 1's actors");
        assertEquals(actorOf(engine, bram), bramView.actors().get(0).id());

        // Walking to plc:1:1 reveals plc:2:1, whose edges then become visible too.
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(bram, actorOf(engine, bram), null,
                        List.of(new TaskSpec.Travel(new PlaceId(1, 1))))));
        while (engine.peekNextDueMs().isPresent()) {
            engine.advanceToNextEvent();
        }
        engine.drainEvents();

        OwnerObservation revealed = ObservationProjector.projectOwner(engine.view(), bram,
                COMPOSER);
        PlaceDto ridge = revealed.places().stream()
                .filter(place -> place.id().equals(new PlaceId(1, 1)))
                .findFirst().orElseThrow();
        assertEquals(List.of(new PlaceId(0, 1), new PlaceId(2, 1)),
                ridge.edges().stream().map(PlaceDto.EdgeDto::to).toList(),
                "after discovering plc:2:1, plc:1:1's edges must show both neighbors");
    }

    @Test
    void actorDtoExposesQueueStateAndPlaceLook() {
        WildsEngine engine = twoOwnerEngine();
        OwnerId ash = owner(engine, 1L);
        ActorId ashActor = actorOf(engine, ash);
        assertInstanceOf(Phase1Result.Accepted.class, engine.apply(
                new WorldCommand.EnqueueTasks(ash, ashActor, null,
                        List.of(new TaskSpec.Wait(4_000, 2)))));

        OwnerObservation observation = ObservationProjector.projectOwner(engine.view(), ash,
                COMPOSER);
        ActorDto dto = observation.actors().get(0);
        assertEquals(ashActor, dto.id());
        assertTrue(dto.revision() > 0, "dto exposes the actor revision");
        assertTrue(dto.energyMilliAtNow() >= 0 && dto.energyMilliAtNow() <= 100_000);
        assertNotNull(dto.activeTask(), "dto exposes the running wait");
        assertEquals(2, dto.activeTask().batchesLeft(),
                "the first batch of Wait(4_000, 2) reports 2 batches left");
        assertEquals(0, dto.pendingQueue().size());
        assertEquals(com.kotharworks.wilds.state.QueueFailurePolicy.ADVANCE, dto.failPolicy());
        assertTrue(dto.look().contains(dto.placeId().toWire()),
                "look text must describe the actor's current place");
        assertNotEquals("", dto.look());
    }

    @Test
    void examineRejectsUndiscoveredPlacesAndForeignActors() {
        WildsEngine engine = twoOwnerEngine();
        OwnerId ash = owner(engine, 1L);
        OwnerId bram = owner(engine, 2L);

        // Undiscovered (but live) place: fog semantics -> UNKNOWN_TARGET with no existence oracle.
        ObservationProjector.ObservationRejectedException undiscovered =
                assertThrows(ObservationProjector.ObservationRejectedException.class,
                        () -> ObservationProjector.examine(engine.view(), bram,
                                new InspectTarget.PlaceTarget(new PlaceId(2, 1)),
                                ZoomLevel.LOOK, COMPOSER));
        assertEquals(ErrorCode.UNKNOWN_TARGET, undiscovered.errorCode());
        assertEquals("no live place plc:2:1", undiscovered.getMessage());

        // Missing place entirely: identical UNKNOWN_TARGET pattern (no oracle).
        ObservationProjector.ObservationRejectedException missing =
                assertThrows(ObservationProjector.ObservationRejectedException.class,
                        () -> ObservationProjector.examine(engine.view(), bram,
                                new InspectTarget.PlaceTarget(new PlaceId(9, 1)),
                                ZoomLevel.LOOK, COMPOSER));
        assertEquals(ErrorCode.UNKNOWN_TARGET, missing.errorCode());
        assertEquals("no live place plc:9:1", missing.getMessage());

        // Examining discovered plc:1:1 before discovering plc:2:1 must omit plc:2:1 from prose.
        String ridgeLook = ObservationProjector.examine(engine.view(), bram,
                new InspectTarget.PlaceTarget(new PlaceId(1, 1)), ZoomLevel.LOOK, COMPOSER);
        String ridgeExamine = ObservationProjector.examine(engine.view(), bram,
                new InspectTarget.PlaceTarget(new PlaceId(1, 1)), ZoomLevel.EXAMINE, COMPOSER);
        assertTrue(!ridgeLook.contains("plc:2:1"), "LOOK prose must omit undiscovered neighbor");
        assertTrue(!ridgeExamine.contains("plc:2:1"),
                "EXAMINE prose must omit undiscovered neighbor");

        // Fogged WorldView handed to composer must redact global worldHash to 0L.
        ObservationProjector.examine(engine.view(), bram,
                new InspectTarget.PlaceTarget(new PlaceId(0, 1)), ZoomLevel.GLANCE,
                (fogged, target, zoom) -> {
                    assertEquals(0L, fogged.worldHash(),
                            "fogged view must not expose the global unfogged worldHash");
                    return "ok";
                });

        // Foreign actor: FORBIDDEN.
        ObservationProjector.ObservationRejectedException foreign =
                assertThrows(ObservationProjector.ObservationRejectedException.class,
                        () -> ObservationProjector.examine(engine.view(), bram,
                                new InspectTarget.ActorTarget(actorOf(engine, ash)),
                                ZoomLevel.LOOK, COMPOSER));
        assertEquals(ErrorCode.FORBIDDEN, foreign.errorCode());

        // Missing actor: UNKNOWN_TARGET.
        ObservationProjector.ObservationRejectedException ghost =
                assertThrows(ObservationProjector.ObservationRejectedException.class,
                        () -> ObservationProjector.examine(engine.view(), bram,
                                new InspectTarget.ActorTarget(new ActorId(9, 1)),
                                ZoomLevel.LOOK, COMPOSER));
        assertEquals(ErrorCode.UNKNOWN_TARGET, ghost.errorCode());

        // Own targets compose through the fogged view without restriction.
        String placeText = ObservationProjector.examine(engine.view(), ash,
                new InspectTarget.PlaceTarget(new PlaceId(0, 1)), ZoomLevel.LOOK, COMPOSER);
        assertTrue(placeText.contains("[plc:0:1]"));
        String actorText = ObservationProjector.examine(engine.view(), ash,
                new InspectTarget.ActorTarget(actorOf(engine, ash)), ZoomLevel.LOOK, COMPOSER);
        assertTrue(actorText.contains("[act:0:1]"));
    }

    @Test
    void projectOwnerRejectsUnknownViewer() {
        WildsEngine engine = twoOwnerEngine();
        assertThrows(java.util.NoSuchElementException.class,
                () -> ObservationProjector.projectOwner(engine.view(), new OwnerId(9, 1),
                        COMPOSER));
    }

    private static void assertNeverShows(OwnerObservation observation, PlaceId placeId,
                                         String message) {
        for (PlaceDto place : observation.places()) {
            assertTrue(!place.id().equals(placeId), message);
            for (PlaceDto.EdgeDto edge : place.edges()) {
                assertTrue(!edge.to().equals(placeId), message);
            }
        }
    }
}
