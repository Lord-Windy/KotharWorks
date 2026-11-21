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
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Deterministic structural stub composer: crisp rows/edges/gauge text straight from the
 * live {@link PlaceRow}/{@link ActorRow} and the world seed — no prose grammars yet.
 * A future {@code libs/procgen} adapter replaces it wholesale via the
 * {@link DescriptionComposer} seam.
 */
public final class StubDescriptionComposer implements DescriptionComposer {

    public static final StubDescriptionComposer INSTANCE = new StubDescriptionComposer();

    @Override
    public String compose(WorldState state, InspectTarget target, ZoomLevel zoom) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(zoom, "zoom");
        return switch (target) {
            case InspectTarget.PlaceTarget placeTarget -> composePlace(state, placeTarget.placeId(), zoom);
            case InspectTarget.ActorTarget actorTarget -> composeActor(state, actorTarget.actorId(), zoom);
        };
    }

    private String composePlace(WorldState state, PlaceId placeId, ZoomLevel zoom) {
        PlaceRow row = state.places().getOrNull(placeId);
        if (row == null) {
            throw new NoSuchElementException("no live place " + placeId.toWire());
        }
        StringBuilder text = new StringBuilder()
                .append('[').append(row.id().toWire()).append("] ")
                .append(row.label())
                .append(" (").append(row.kindId().slug()).append(')');
        switch (zoom) {
            case GLANCE -> {
                // Headline only.
            }
            case LOOK -> {
                text.append(" | paths:");
                for (PlaceEdge edge : row.edges()) {
                    text.append(' ').append(edge.to().toWire());
                }
            }
            case EXAMINE -> {
                text.append(" | seed 0x").append(Long.toHexString(state.seed().value()));
                for (PlaceEdge edge : row.edges()) {
                    text.append(System.lineSeparator())
                            .append("  -> ").append(edge.to().toWire())
                            .append(" (").append(edge.travelDurationMs()).append("ms)");
                }
            }
        }
        return text.toString();
    }

    private String composeActor(WorldState state, ActorId actorId, ZoomLevel zoom) {
        ActorRow row = state.actors().getOrNull(actorId);
        if (row == null) {
            throw new NoSuchElementException("no live actor " + actorId.toWire());
        }
        StringBuilder text = new StringBuilder()
                .append('[').append(row.id().toWire()).append("] ")
                .append(row.label())
                .append(" at [").append(row.placeId().toWire()).append(']');
        switch (zoom) {
            case GLANCE -> {
                // Headline only.
            }
            case LOOK -> {
                appendEnergy(text, state, row);
            }
            case EXAMINE -> {
                appendEnergy(text, state, row);
                if (row.activeTask() == null) {
                    text.append(System.lineSeparator()).append("  idle");
                } else {
                    ActiveTask task = row.activeTask();
                    text.append(System.lineSeparator())
                            .append("  task: ").append(renderSpec(task.spec()))
                            .append(" (started ").append(task.startedAtMs())
                            .append("ms, due ").append(task.dueAtMs())
                            .append("ms, ticket ").append(task.heapTicket())
                            .append(", batches left ").append(task.batchesLeft()).append(')');
                }
                text.append(System.lineSeparator())
                        .append("  queue (").append(row.pendingQueue().size()).append("):");
                for (TaskSpec spec : row.pendingQueue()) {
                    text.append(System.lineSeparator())
                            .append("    - ").append(renderSpec(spec));
                }
            }
        }
        return text.toString();
    }

    private static void appendEnergy(StringBuilder text, WorldState state, ActorRow row) {
        text.append(" energy ").append(row.energyMilli().valueMilliAt(state.nowMs()))
                .append('/').append(row.energyMilli().maxMilli());
    }

    private static String renderSpec(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.Wait(long durationMs, int batches) -> "wait " + durationMs + "ms x" + batches;
            case TaskSpec.Travel(PlaceId destination) -> "travel -> " + destination.toWire();
        };
    }
}
