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
package com.kotharworks.wilds.replay;

import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.TaskSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Canonical, durable JSON form of every {@link WorldEvent} variant: the stable
 * lowercase {@link #kind}, the variant-field {@link #payload}, and the stored
 * {@link #encode} form (payload plus a trailing {@code scopeOwner} key). Hosts
 * persist {@link #encode} output and the {@link #kind} string verbatim, so this
 * class — not any transport adapter — owns the at-rest format; it is pinned
 * byte-for-byte by {@code EventPayloadCodecTest}. Cursor, occurrence time and
 * the two summaries travel beside the payload and are handed back to
 * {@link #decode}.
 */
public final class EventPayloadCodec {

    private EventPayloadCodec() {
    }

    /** The stable lowercase kind of {@code event}. */
    public static String kind(WorldEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case WorldEvent.OwnerRegistered _ -> "owner_registered";
            case WorldEvent.TaskStarted _ -> "task_started";
            case WorldEvent.TaskBatchCompleted _ -> "task_batch_completed";
            case WorldEvent.TaskCancelled _ -> "task_cancelled";
            case WorldEvent.TaskFailed _ -> "task_failed";
            case WorldEvent.PlaceDiscovered _ -> "place_discovered";
        };
    }

    /** The variant fields of {@code event} as a JSON object (no envelope, no scope owner). */
    public static JsonCodec.JsonObj payload(WorldEvent event) {
        Objects.requireNonNull(event, "event");
        return switch (event) {
            case WorldEvent.OwnerRegistered e -> obj(
                    entry("ownerId", str(e.ownerId().toWire())),
                    entry("initialActorId", str(e.initialActorId().toWire())));
            case WorldEvent.TaskStarted e -> obj(
                    entry("actorId", str(e.actorId().toWire())),
                    entry("task", task(e.task())));
            case WorldEvent.TaskBatchCompleted e -> obj(
                    entry("actorId", str(e.actorId().toWire())),
                    entry("spec", CommandCodec.encodeSpec(e.spec())),
                    entry("batchesLeftAfter", new JsonCodec.JsonInt(e.batchesLeftAfter())));
            case WorldEvent.TaskCancelled e -> obj(
                    entry("actorId", str(e.actorId().toWire())),
                    entry("cancelledActive", new JsonCodec.JsonBool(e.cancelledActive())),
                    entry("clearedPendingCount", new JsonCodec.JsonInt(e.clearedPendingCount())));
            case WorldEvent.TaskFailed e -> obj(
                    entry("actorId", str(e.actorId().toWire())),
                    entry("spec", CommandCodec.encodeSpec(e.spec())),
                    entry("failureCode", str(e.failureCode().name())));
            case WorldEvent.PlaceDiscovered e -> obj(
                    entry("placeId", str(e.placeId().toWire())),
                    entry("discoveredByActor", str(e.discoveredByActor().toWire())));
        };
    }

    /** The stored form: {@link #payload} followed by the {@code scopeOwner} wire id. */
    public static String encode(WorldEvent event) {
        JsonCodec.JsonObj payload = payload(event);
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>(payload.entries().size() + 1);
        entries.addAll(payload.entries());
        entries.add(entry("scopeOwner", str(event.scopeOwner().toWire())));
        return JsonCodec.write(new JsonCodec.JsonObj(entries));
    }

    /**
     * Rebuilds one engine event from its stored parts.
     *
     * @throws IllegalArgumentException on an unknown kind or a malformed payload
     */
    public static WorldEvent decode(String kind, long cursor, long occurredMs,
            String publicSummary, String privateSummary, String storedPayload) {
        Objects.requireNonNull(kind, "kind");
        JsonCodec.JsonObj obj = expectObj(JsonCodec.parse(storedPayload), "payload");
        OwnerId scopeOwner = OwnerId.parse(reqStr(obj, "scopeOwner"));
        return switch (kind) {
            case "owner_registered" -> new WorldEvent.OwnerRegistered(
                    OwnerId.parse(reqStr(obj, "ownerId")),
                    ActorId.parse(reqStr(obj, "initialActorId")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_started" -> new WorldEvent.TaskStarted(
                    ActorId.parse(reqStr(obj, "actorId")),
                    decodeTask(expectObj(obj.getOrNull("task"), "task")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_batch_completed" -> new WorldEvent.TaskBatchCompleted(
                    ActorId.parse(reqStr(obj, "actorId")),
                    decodeSpec(expectObj(obj.getOrNull("spec"), "spec")),
                    reqInt(obj, "batchesLeftAfter"),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_cancelled" -> new WorldEvent.TaskCancelled(
                    ActorId.parse(reqStr(obj, "actorId")),
                    reqBool(obj, "cancelledActive"),
                    reqInt(obj, "clearedPendingCount"),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "task_failed" -> new WorldEvent.TaskFailed(
                    ActorId.parse(reqStr(obj, "actorId")),
                    decodeSpec(expectObj(obj.getOrNull("spec"), "spec")),
                    errorCode(reqStr(obj, "failureCode")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            case "place_discovered" -> new WorldEvent.PlaceDiscovered(
                    PlaceId.parse(reqStr(obj, "placeId")),
                    ActorId.parse(reqStr(obj, "discoveredByActor")),
                    cursor, occurredMs, scopeOwner, publicSummary, privateSummary);
            default -> throw new IllegalArgumentException(
                    "unknown world event kind: \"" + kind + "\"");
        };
    }

    private static JsonCodec.JsonObj task(ActiveTask task) {
        return obj(
                entry("startedAtMs", new JsonCodec.JsonInt(task.startedAtMs())),
                entry("dueAtMs", new JsonCodec.JsonInt(task.dueAtMs())),
                entry("heapTicket", new JsonCodec.JsonInt(task.heapTicket())),
                entry("spec", CommandCodec.encodeSpec(task.spec())),
                entry("batchesLeft", new JsonCodec.JsonInt(task.batchesLeft())));
    }

    private static ActiveTask decodeTask(JsonCodec.JsonObj obj) {
        return new ActiveTask(
                reqLong(obj, "startedAtMs"),
                reqLong(obj, "dueAtMs"),
                reqLong(obj, "heapTicket"),
                decodeSpec(expectObj(obj.getOrNull("spec"), "spec")),
                reqInt(obj, "batchesLeft"));
    }

    private static TaskSpec decodeSpec(JsonCodec.JsonObj obj) {
        String type = reqStr(obj, "type");
        return switch (type) {
            case "wait" -> new TaskSpec.Wait(reqLong(obj, "durationMs"), reqInt(obj, "batches"));
            case "travel" -> new TaskSpec.Travel(PlaceId.parse(reqStr(obj, "destination")));
            default -> throw new IllegalArgumentException(
                    "unknown task spec type: \"" + type + "\"");
        };
    }

    private static ErrorCode errorCode(String name) {
        try {
            return ErrorCode.valueOf(name);
        } catch (IllegalArgumentException unknownCode) {
            throw new IllegalArgumentException("unknown failure code: \"" + name + "\"");
        }
    }

    private static JsonCodec.JsonObj obj(JsonCodec.JsonObj.Entry... entries) {
        return new JsonCodec.JsonObj(List.of(entries));
    }

    private static JsonCodec.JsonObj.Entry entry(String key, JsonCodec.JsonValue value) {
        return JsonCodec.JsonObj.entry(key, value);
    }

    private static JsonCodec.JsonStr str(String value) {
        return new JsonCodec.JsonStr(value);
    }

    private static JsonCodec.JsonObj expectObj(JsonCodec.JsonValue value, String key) {
        if (value instanceof JsonCodec.JsonObj obj) {
            return obj;
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON object");
    }

    private static String reqStr(JsonCodec.JsonObj obj, String key) {
        if (obj.getOrNull(key) instanceof JsonCodec.JsonStr str) {
            return str.value();
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON string");
    }

    private static long reqLong(JsonCodec.JsonObj obj, String key) {
        if (obj.getOrNull(key) instanceof JsonCodec.JsonInt number) {
            return number.value();
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON integer");
    }

    private static int reqInt(JsonCodec.JsonObj obj, String key) {
        long value = reqLong(obj, key);
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "event payload field \"" + key + "\" exceeds int range: " + value);
        }
    }

    private static boolean reqBool(JsonCodec.JsonObj obj, String key) {
        if (obj.getOrNull(key) instanceof JsonCodec.JsonBool bool) {
            return bool.value();
        }
        throw new IllegalArgumentException(
                "event payload field \"" + key + "\" must be a JSON boolean");
    }
}
