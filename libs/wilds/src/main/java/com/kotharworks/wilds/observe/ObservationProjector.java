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

import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.worldgen.DescriptionComposer;
import com.kotharworks.wilds.worldgen.DescriptionComposer.InspectTarget;
import com.kotharworks.wilds.worldgen.DescriptionComposer.ZoomLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Fog-of-war projector: derives everything a client may see from a deeply immutable
 * {@link WorldView} — never from raw mutable state rows. Strict isolation by
 * construction: the fogged view contains only the viewer's own row, the viewer's own
 * actors, only the viewer's discovered places, edges filtered to discovered
 * destinations (an undiscovered neighbor id such as {@code plc:2:1} viewed from
 * {@code plc:0:1} can never leak through {@code PlaceDto(plc:1:1).edges()}), and a
 * seat-key map reduced to the viewer.
 */
public final class ObservationProjector {

    private ObservationProjector() {
    }

    /**
     * Projects the complete {@link OwnerObservation} for {@code viewer}: their
     * {@link OwnerDto}, their owned {@link ActorDto}s (revision, live energy, active
     * task, pending queue, failure policy, and the composed {@code look} of the place
     * they stand on), and one fogged {@link PlaceDto} per discovered place.
     *
     * @throws NoSuchElementException when {@code viewer} is not a live owner
     */
    public static OwnerObservation projectOwner(WorldView view, OwnerId viewer,
                                                DescriptionComposer composer) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(composer, "composer");
        OwnerRow viewerRow = view.owner(viewer).orElseThrow(
                () -> new NoSuchElementException("no live owner " + viewer.toWire()));
        WorldView fogged = foggedView(view, viewerRow);

        OwnerDto ownerDto = new OwnerDto(viewerRow.id(), viewerRow.externalSeatKey(),
                viewerRow.name(), viewerRow.revision(), viewerRow.discoveredPlaces());
        List<ActorDto> actorDtos = new ArrayList<>(fogged.actors().size());
        for (ActorRow actor : fogged.actors()) {
            String look = composer.compose(fogged, new InspectTarget.PlaceTarget(actor.placeId()),
                    ZoomLevel.LOOK);
            actorDtos.add(new ActorDto(actor.id(), actor.label(), actor.revision(),
                    actor.placeId(), actor.energyMilli().valueMilliAt(fogged.nowMs()),
                    actor.activeTask(), actor.pendingQueue(), actor.failPolicy(), look));
        }
        List<PlaceDto> placeDtos = new ArrayList<>(fogged.places().size());
        for (PlaceRow place : fogged.places()) {
            List<PlaceDto.EdgeDto> edges = new ArrayList<>(place.edges().size());
            for (PlaceEdge edge : place.edges()) {
                edges.add(new PlaceDto.EdgeDto(edge.to(), edge.travelDurationMs()));
            }
            placeDtos.add(new PlaceDto(place.id(), place.kindId().slug(), place.label(), edges));
        }
        return new OwnerObservation(ownerDto, List.copyOf(actorDtos), List.copyOf(placeDtos));
    }

    /**
     * Composes the inspection text of {@code target} as seen by {@code viewer} at
     * {@code zoom}, strictly through the fogged view.
     *
     * @throws ObservationRejectedException with {@code UNKNOWN_TARGET} for an actor or
     *                                      place that does not exist, or a live place
     *                                      outside the viewer's discovered set (fog
     *                                      semantics: to this viewer it does not exist)
     * @throws ObservationRejectedException with {@code FORBIDDEN} for a live actor owned
     *                                      by another owner (private target)
     */
    public static String examine(WorldView view, OwnerId viewer, InspectTarget target,
                                 ZoomLevel zoom, DescriptionComposer composer) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(zoom, "zoom");
        Objects.requireNonNull(composer, "composer");
        OwnerRow viewerRow = view.owner(viewer).orElseThrow(
                () -> new NoSuchElementException("no live owner " + viewer.toWire()));
        WorldView fogged = foggedView(view, viewerRow);
        switch (target) {
            case InspectTarget.ActorTarget actorTarget -> {
                if (fogged.actor(actorTarget.actorId()).isEmpty()) {
                    if (view.actor(actorTarget.actorId()).isPresent()) {
                        throw new ObservationRejectedException(ErrorCode.FORBIDDEN,
                                "actor " + actorTarget.actorId().toWire()
                                        + " is not owned by " + viewer.toWire());
                    }
                    throw new ObservationRejectedException(ErrorCode.UNKNOWN_TARGET,
                            "no live actor " + actorTarget.actorId().toWire());
                }
            }
            case InspectTarget.PlaceTarget placeTarget -> {
                PlaceId placeId = placeTarget.placeId();
                if (fogged.place(placeId).isEmpty()) {
                    throw new ObservationRejectedException(ErrorCode.UNKNOWN_TARGET,
                            view.place(placeId).isPresent()
                                    ? "place " + placeId.toWire() + " is not discovered"
                                    : "no live place " + placeId.toWire());
                }
            }
        }
        return composer.compose(fogged, target, zoom);
    }

    /**
     * The viewer-scoped copy of {@code view}: only the viewer's owner row, only their
     * actors, only their discovered places (edges filtered to discovered destinations),
     * and the seat-key map reduced to the viewer.
     */
    private static WorldView foggedView(WorldView view, OwnerRow viewer) {
        TreeSet<PlaceId> discovered = new TreeSet<>(viewer.discoveredPlaces());
        List<PlaceRow> foggedPlaces = new ArrayList<>(view.places().size());
        for (PlaceRow place : view.places()) {
            if (!discovered.contains(place.id())) {
                continue;
            }
            List<PlaceEdge> visibleEdges = new ArrayList<>(place.edges().size());
            for (PlaceEdge edge : place.edges()) {
                if (discovered.contains(edge.to())) {
                    visibleEdges.add(edge);
                }
            }
            foggedPlaces.add(new PlaceRow(place.id(), place.kindId(), place.label(),
                    visibleEdges));
        }
        List<ActorRow> foggedActors = view.actorsOwnedBy(viewer.id());
        Map<Long, OwnerId> viewerSeatKeys = new TreeMap<>();
        for (Map.Entry<Long, OwnerId> entry : view.ownersBySeatKey().entrySet()) {
            if (entry.getValue().equals(viewer.id())) {
                viewerSeatKeys.put(entry.getKey(), entry.getValue());
            }
        }
        return new WorldView(view.seed(), view.rulesVersion(), view.nowMs(), view.worldHash(),
                List.of(viewer), List.copyOf(foggedPlaces), foggedActors, viewerSeatKeys);
    }

    /** Rejection of an examine request; carries the deterministic {@link ErrorCode}. */
    public static final class ObservationRejectedException extends RuntimeException {

        private final ErrorCode errorCode;

        public ObservationRejectedException(ErrorCode errorCode, String message) {
            super(Objects.requireNonNull(message, "message"));
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        }

        public ErrorCode errorCode() {
            return errorCode;
        }
    }
}
