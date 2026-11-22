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

import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonArr;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;
import com.kotharworks.wilds.replay.JsonCodec.JsonValue;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.TaskSpec;

/**
 * Wire serialization of stored world events. The full (seat-private) form
 * carries the summary plus the decoded payload; {@link #toPublicJson} emits
 * only {@code cursor}/{@code occurredMs}/{@code kind}/{@code publicSummary} —
 * the private summary and payload structurally cannot leak into public feeds.
 */
public final class EventCodec {

    private EventCodec() {
    }

    /** The lowercase event kind ({@code SSE event:} name). */
    public static String kind(StoredWorldEvent event) {
        return event.kind();
    }

    /** Full serialization for the owning seat (REST + SSE private streams). */
    public static String toJson(StoredWorldEvent event) {
        return JsonCodec.write(toJsonValue(event));
    }

    /** The full event as an embeddable JSON value. */
    public static JsonCodec.JsonValue toJsonValue(StoredWorldEvent event) {
        Objects.requireNonNull(event, "event");
        List<JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonObj.entry("cursor", new JsonInt(event.cursor())));
        entries.add(JsonObj.entry("occurredMs", new JsonInt(event.occurredMs())));
        entries.add(JsonObj.entry("kind", ApiJson.str(event.kind())));
        if (event.seatId() != null) {
            entries.add(JsonObj.entry("seatId", new JsonInt(event.seatId())));
        }
        entries.add(JsonObj.entry("publicSummary", event.publicSummary() == null
                ? JsonCodec.JsonNull.INSTANCE
                : ApiJson.str(event.publicSummary())));
        entries.add(JsonObj.entry("privateSummary", event.privateSummary() == null
                ? JsonCodec.JsonNull.INSTANCE
                : ApiJson.str(event.privateSummary())));
        entries.add(JsonObj.entry("payload", event.event() == null
                ? JsonCodec.JsonNull.INSTANCE
                : payload(event.event())));
        return new JsonObj(entries);
    }

    /** Public-feed serialization: private detail is structurally absent. */
    public static String toPublicJson(StoredWorldEvent event) {
        return JsonCodec.write(toPublicJsonValue(event));
    }

    /** The public projection as an embeddable JSON value. */
    public static JsonCodec.JsonValue toPublicJsonValue(StoredWorldEvent event) {
        Objects.requireNonNull(event, "event");
        return new JsonObj(List.of(
                JsonObj.entry("cursor", new JsonInt(event.cursor())),
                JsonObj.entry("occurredMs", new JsonInt(event.occurredMs())),
                JsonObj.entry("kind", ApiJson.str(event.kind())),
                JsonObj.entry("publicSummary", event.publicSummary() == null
                        ? JsonCodec.JsonNull.INSTANCE
                        : ApiJson.str(event.publicSummary()))));
    }

    /**
     * The wire payload object for one engine event. Persistence reuses exactly
     * this shape inside {@code world_events.payload_json}, so a recovered row
     * serializes byte-identically to the live SSE/REST frame.
     */
    public static JsonObj payloadJson(WorldEvent event) {
        return payload(event);
    }

    private static JsonObj payload(WorldEvent event) {
        return switch (event) {
            case WorldEvent.OwnerRegistered e -> new JsonObj(List.of(
                    JsonObj.entry("ownerId", ApiJson.str(e.ownerId().toWire())),
                    JsonObj.entry("initialActorId", ApiJson.str(e.initialActorId().toWire()))));
            case WorldEvent.TaskStarted e -> new JsonObj(List.of(
                    JsonObj.entry("actorId", ApiJson.str(e.actorId().toWire())),
                    JsonObj.entry("task", task(e.task()))));
            case WorldEvent.TaskBatchCompleted e -> new JsonObj(List.of(
                    JsonObj.entry("actorId", ApiJson.str(e.actorId().toWire())),
                    JsonObj.entry("spec", spec(e.spec())),
                    JsonObj.entry("batchesLeftAfter", new JsonInt(e.batchesLeftAfter()))));
            case WorldEvent.TaskCancelled e -> new JsonObj(List.of(
                    JsonObj.entry("actorId", ApiJson.str(e.actorId().toWire())),
                    JsonObj.entry("cancelledActive", new JsonCodec.JsonBool(e.cancelledActive())),
                    JsonObj.entry("clearedPendingCount", new JsonInt(e.clearedPendingCount()))));
            case WorldEvent.TaskFailed e -> new JsonObj(List.of(
                    JsonObj.entry("actorId", ApiJson.str(e.actorId().toWire())),
                    JsonObj.entry("spec", spec(e.spec())),
                    JsonObj.entry("failureCode", ApiJson.str(e.failureCode().name()))));
            case WorldEvent.PlaceDiscovered e -> new JsonObj(List.of(
                    JsonObj.entry("placeId", ApiJson.str(e.placeId().toWire())),
                    JsonObj.entry("discoveredByActor", ApiJson.str(e.discoveredByActor().toWire()))));
        };
    }

    private static JsonObj task(ActiveTask task) {
        return new JsonObj(List.of(
                JsonObj.entry("startedAtMs", new JsonInt(task.startedAtMs())),
                JsonObj.entry("dueAtMs", new JsonInt(task.dueAtMs())),
                JsonObj.entry("heapTicket", new JsonInt(task.heapTicket())),
                JsonObj.entry("spec", spec(task.spec())),
                JsonObj.entry("batchesLeft", new JsonInt(task.batchesLeft()))));
    }

    /** Shared by {@code EventCodec} and {@code ObservationJson}. */
    public static JsonObj spec(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.Wait(long durationMs, int batches) -> new JsonObj(List.of(
                    JsonObj.entry("type", ApiJson.str("wait")),
                    JsonObj.entry("durationMs", new JsonInt(durationMs)),
                    JsonObj.entry("batches", new JsonInt(batches))));
            case TaskSpec.Travel(var destination) -> new JsonObj(List.of(
                    JsonObj.entry("type", ApiJson.str("travel")),
                    JsonObj.entry("destination", ApiJson.str(destination.toWire()))));
        };
    }

    /** Decodes a task-spec array from the command envelope body. */
    public static List<TaskSpec> decodeTasks(JsonCodec.JsonValue value, String field) {
        if (!(value instanceof JsonArr arr)) {
            throw new IllegalArgumentException("field \"" + field + "\" must be a JSON array");
        }
        List<TaskSpec> tasks = new ArrayList<>(arr.values().size());
        for (JsonValue entry : arr.values()) {
            if (!(entry instanceof JsonObj obj)) {
                throw new IllegalArgumentException(
                        "field \"" + field + "\" entries must be JSON objects");
            }
            String type = ApiJson.requireString(obj, "type");
            tasks.add(switch (type) {
                case "wait" -> {
                    long durationMs = ApiJson.requireLong(obj, "durationMs");
                    long batchesLong = ApiJson.requireLong(obj, "batches");
                    if (batchesLong < 1 || batchesLong > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException(
                                "field \"batches\" must be 1.." + Integer.MAX_VALUE
                                        + ", got: " + batchesLong);
                    }
                    yield new TaskSpec.Wait(durationMs, (int) batchesLong);
                }
                case "travel" -> new TaskSpec.Travel(
                        com.kotharworks.wilds.id.PlaceId.parse(
                                ApiJson.requireString(obj, "destination")));
                default -> throw new IllegalArgumentException(
                        "unknown task spec type: \"" + type + "\"");
            });
        }
        return List.copyOf(tasks);
    }
}
