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
package com.kotharworks.wilds.server.adapters.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.kotharworks.wilds.observe.ActorDto;
import com.kotharworks.wilds.observe.OwnerDto;
import com.kotharworks.wilds.observe.OwnerObservation;
import com.kotharworks.wilds.observe.PlaceDto;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.TaskSpec;

/**
 * Explicit reflection-free serializers for fogged observations and the rule
 * catalog. Everything renders through {@link JsonCodec}, so wire forms stay
 * canonical and escaped.
 */
public final class ObservationJson {

    private ObservationJson() {
    }

    /**
     * Serializes the complete owner observation (owner + actors + fogged places)
     * plus the view's logical clock, which browser consoles use to resync their
     * task-progress interpolation against server time.
     */
    public static String observation(OwnerObservation observation, long nowMs) {
        Objects.requireNonNull(observation, "observation");
        List<JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonObj.entry("nowMs", ApiJson.num(nowMs)));
        entries.add(JsonObj.entry("owner", owner(observation.owner())));
        entries.add(JsonObj.entry("actors", new JsonCodec.JsonArr(observation.actors().stream()
                .map(ObservationJson::actor)
                .map(actor -> (JsonCodec.JsonValue) actor)
                .toList())));
        entries.add(JsonObj.entry("places", new JsonCodec.JsonArr(observation.places().stream()
                .map(ObservationJson::place)
                .map(place -> (JsonCodec.JsonValue) place)
                .toList())));
        return JsonCodec.write(new JsonObj(entries));
    }

    /** Serializes the rule catalog plus the pinned rules version. */
    public static String rules(RuleRegistry rules, int rulesVersion) {
        Objects.requireNonNull(rules, "rules");
        List<JsonCodec.JsonValue> placeKinds = new ArrayList<>();
        for (RuleRegistry.PlaceKindDef kind : rules.placeKinds().values()) {
            placeKinds.add(new JsonObj(List.of(
                    JsonObj.entry("id", ApiJson.str(kind.id().slug())),
                    JsonObj.entry("name", ApiJson.str(kind.name())))));
        }
        List<JsonCodec.JsonValue> taskKinds = new ArrayList<>();
        for (RuleRegistry.TaskKindDef kind : rules.taskKinds().values()) {
            taskKinds.add(new JsonObj(List.of(
                    JsonObj.entry("id", ApiJson.str(kind.id().slug())),
                    JsonObj.entry("name", ApiJson.str(kind.name())),
                    JsonObj.entry("minDurationMs", ApiJson.num(kind.minDurationMs())),
                    JsonObj.entry("maxDurationMs", ApiJson.num(kind.maxDurationMs())),
                    JsonObj.entry("maxBatches", ApiJson.num(kind.maxBatches())))));
        }
        return JsonCodec.write(new JsonObj(List.of(
                JsonObj.entry("rulesVersion", ApiJson.num(rulesVersion)),
                JsonObj.entry("placeKinds", new JsonCodec.JsonArr(placeKinds)),
                JsonObj.entry("taskKinds", new JsonCodec.JsonArr(taskKinds)))));
    }

    private static JsonObj owner(OwnerDto owner) {
        List<JsonCodec.JsonValue> discovered = new ArrayList<>();
        for (var placeId : owner.discoveredPlaces()) {
            discovered.add(ApiJson.str(placeId.toWire()));
        }
        return new JsonObj(List.of(
                JsonObj.entry("id", ApiJson.str(owner.id().toWire())),
                JsonObj.entry("seatId", ApiJson.num(owner.externalSeatKey())),
                JsonObj.entry("name", ApiJson.str(owner.name())),
                JsonObj.entry("revision", ApiJson.num(owner.revision())),
                JsonObj.entry("discoveredPlaces", new JsonCodec.JsonArr(discovered))));
    }

    private static JsonObj actor(ActorDto actor) {
        return new JsonObj(List.of(
                JsonObj.entry("id", ApiJson.str(actor.id().toWire())),
                JsonObj.entry("label", ApiJson.str(actor.label())),
                JsonObj.entry("revision", ApiJson.num(actor.revision())),
                JsonObj.entry("placeId", ApiJson.str(actor.placeId().toWire())),
                JsonObj.entry("energy", ApiJson.num(actor.energyMilliAtNow())),
                JsonObj.entry("activeTask", actor.activeTask() == null
                        ? JsonCodec.JsonNull.INSTANCE
                        : activeTask(actor.activeTask())),
                JsonObj.entry("pendingQueue", queue(actor.pendingQueue())),
                JsonObj.entry("failPolicy", ApiJson.str(actor.failPolicy().name())),
                JsonObj.entry("look", ApiJson.str(actor.look()))));
    }

    private static JsonObj activeTask(ActiveTask task) {
        return new JsonObj(List.of(
                JsonObj.entry("startedAtMs", ApiJson.num(task.startedAtMs())),
                JsonObj.entry("dueAtMs", ApiJson.num(task.dueAtMs())),
                JsonObj.entry("heapTicket", ApiJson.num(task.heapTicket())),
                JsonObj.entry("spec", EventCodec.spec(task.spec())),
                JsonObj.entry("batchesLeft", ApiJson.num(task.batchesLeft()))));
    }

    private static JsonCodec.JsonArr queue(List<TaskSpec> pending) {
        return new JsonCodec.JsonArr(pending.stream()
                .map(EventCodec::spec)
                .map(spec -> (JsonCodec.JsonValue) spec)
                .toList());
    }

    private static JsonObj place(PlaceDto place) {
        List<JsonCodec.JsonValue> edges = new ArrayList<>();
        for (PlaceDto.EdgeDto edge : place.edges()) {
            edges.add(new JsonObj(List.of(
                    JsonObj.entry("to", ApiJson.str(edge.to().toWire())),
                    JsonObj.entry("travelDurationMs", ApiJson.num(edge.travelDurationMs())))));
        }
        return new JsonObj(List.of(
                JsonObj.entry("id", ApiJson.str(place.id().toWire())),
                JsonObj.entry("kind", ApiJson.str(place.kind())),
                JsonObj.entry("label", ApiJson.str(place.label())),
                JsonObj.entry("edges", new JsonCodec.JsonArr(edges))));
    }
}
