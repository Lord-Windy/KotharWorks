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
package com.kotharworks.wilds.server.adapters.postgres;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonBool;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;
import com.kotharworks.wilds.replay.JsonCodec.JsonValue;
import com.kotharworks.wilds.server.adapters.http.EventCodec;
import com.kotharworks.wilds.state.TaskSpec;

/**
 * JSONB codec for the {@code world_events.payload_json} column. Encoded form
 * is {@link EventCodec#payloadJson} plus one {@code scopeOwner} key (the
 * owning owner wire id is not a ticket column, so it travels inside the
 * payload); the extra key is never re-emitted on the wire —
 * {@link EventCodec#payloadJson} serializes variant fields only — so a
 * recovered row's SSE/REST frame is byte-identical to the live one.
 * Cursor, occurrence time, and the two summaries come from dedicated columns
 * and are passed to {@link #decode} by the store.
 */
public final class WorldEventPayloadCodec {

    private WorldEventPayloadCodec() {
    }

    /** Encodes the event with its scope owner embedded. */
    public static String encode(WorldEvent event) {
        Objects.requireNonNull(event, "event");
        JsonObj payload = EventCodec.payloadJson(event);
        List<JsonObj.Entry> entries = new ArrayList<>(payload.entries().size() + 1);
        entries.addAll(payload.entries());
        entries.add(JsonObj.entry("scopeOwner", new JsonStr(event.scopeOwner().toWire())));
        return JsonCodec.write(new JsonObj(entries));
    }

    /**
     * Decodes one full engine event from a persisted row.
     *
     * @param kind            the row's {@code kind} column
     * @param cursor          the row's {@code event_cursor} column
     * @param occurredMs      the row's {@code occurred_ms} column
     * @param publicSummary   the row's {@code public_summary} column
     * @param privateSummary  the row's {@code private_summary} column
     * @param payloadJson     the row's {@code payload_json} column (as text)
     * @throws IllegalArgumentException on an unknown kind or malformed payload
     */
    public static WorldEvent decode(String kind, long cursor, long occurredMs,
            String publicSummary, String privateSummary, String payloadJson) {
        JsonValue parsed = JsonCodec.parse(payloadJson);
        if (!(parsed instanceof JsonObj obj)) {
            throw new IllegalArgumentException("event payload must be a JSON object");
        }
        OwnerId scopeOwner = OwnerId.parse(reqStr(obj, "scopeOwner"));
        return switch (kind) {
            case "owner_registered" -> new WorldEvent.OwnerRegistered(
                    OwnerId.parse(reqStr(obj, "ownerId")),
                    ActorId.parse(reqStr(obj, "initialActorId")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_started" -> new WorldEvent.TaskStarted(
                    ActorId.parse(reqStr(obj, "actorId")),
                    decodeTask(obj.getOrNull("task")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_batch_completed" -> new WorldEvent.TaskBatchCompleted(
                    ActorId.parse(reqStr(obj, "actorId")),
                    decodeSpec(obj.getOrNull("spec")),
                    reqInt(obj, "batchesLeftAfter"),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_cancelled" -> new WorldEvent.TaskCancelled(
                    ActorId.parse(reqStr(obj, "actorId")),
                    reqBool(obj, "cancelledActive"),
                    reqInt(obj, "clearedPendingCount"),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_failed" -> new WorldEvent.TaskFailed(
                    ActorId.parse(reqStr(obj, "actorId")),
                    decodeSpec(obj.getOrNull("spec")),
                    ErrorCode.valueOf(reqStr(obj, "failureCode")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "place_discovered" -> new WorldEvent.PlaceDiscovered(
                    PlaceId.parse(reqStr(obj, "placeId")),
                    ActorId.parse(reqStr(obj, "discoveredByActor")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            default -> throw new IllegalArgumentException(
                    "unknown world event kind: \"" + kind + "\"");
        };
    }

    private static ActiveTask decodeTask(JsonValue value) {
        if (!(value instanceof JsonObj obj)) {
            throw new IllegalArgumentException("event payload field \"task\" must be an object");
        }
        return new ActiveTask(
                reqLong(obj, "startedAtMs"),
                reqLong(obj, "dueAtMs"),
                reqLong(obj, "heapTicket"),
                decodeSpec(obj.getOrNull("spec")),
                reqInt(obj, "batchesLeft"));
    }

    private static TaskSpec decodeSpec(JsonValue value) {
        if (!(value instanceof JsonObj obj)) {
            throw new IllegalArgumentException("event payload field \"spec\" must be an object");
        }
        String type = reqStr(obj, "type");
        return switch (type) {
            case "wait" -> new TaskSpec.Wait(
                    reqLong(obj, "durationMs"),
                    reqInt(obj, "batches"));
            case "travel" -> new TaskSpec.Travel(
                    PlaceId.parse(reqStr(obj, "destination")));
            default -> throw new IllegalArgumentException(
                    "unknown task spec type: \"" + type + "\"");
        };
    }

    private static String reqStr(JsonObj obj, String key) {
        JsonValue value = obj.getOrNull(key);
        if (value instanceof JsonStr str) {
            return str.value();
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON string");
    }

    private static long reqLong(JsonObj obj, String key) {
        JsonValue value = obj.getOrNull(key);
        if (value instanceof JsonInt number) {
            return number.value();
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON integer");
    }

    private static int reqInt(JsonObj obj, String key) {
        long value = reqLong(obj, key);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "event payload field \"" + key + "\" exceeds int range: " + value);
        }
    }

    private static boolean reqBool(JsonObj obj, String key) {
        JsonValue value = obj.getOrNull(key);
        if (value instanceof JsonBool bool) {
            return bool.value();
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON boolean");
    }
}
