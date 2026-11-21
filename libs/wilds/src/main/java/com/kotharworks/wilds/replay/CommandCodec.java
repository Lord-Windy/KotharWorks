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

import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic canonical JSON codec for every {@link WorldCommand} variant and every
 * {@link WalRecord} variant, built on the integers-only {@link JsonCodec} (no float
 * JSON can ever sneak in). Wire form is compact with a fixed key order, so equal
 * commands always encode byte-identically and round-trip is exact:
 * {@code decode(encode(x)).equals(x)}. Missing, wrong-typed, or unknown-type fields are
 * rejected with {@link IllegalArgumentException} before any record is constructed.
 */
public final class CommandCodec {

    private CommandCodec() {
    }

    // ------------------------------------------------------------------ commands

    /** Encodes {@code command} as canonical compact JSON. */
    public static String encode(WorldCommand command) {
        Objects.requireNonNull(command, "command");
        return JsonCodec.write(encodeCommand(command));
    }

    /** Decodes a command produced by {@link #encode}; shape errors are rejected. */
    public static WorldCommand decode(String json) {
        JsonCodec.JsonObj root = expectObj(JsonCodec.parse(json), "command");
        return decodeCommand(root);
    }

    private static WorldCommand decodeCommand(JsonCodec.JsonObj root) {
        String type = expectStr(field(root, "type"), "command type");
        return switch (type) {
            case "registerOwner" -> new WorldCommand.RegisterOwner(
                    expectInt(field(root, "externalSeatKey"), "externalSeatKey"),
                    expectStr(field(root, "ownerName"), "ownerName"),
                    expectStr(field(root, "initialActorLabel"), "initialActorLabel"));
            case "enqueueTasks" -> new WorldCommand.EnqueueTasks(
                    OwnerId.parse(expectStr(field(root, "actorOwner"), "actorOwner")),
                    ActorId.parse(expectStr(field(root, "targetActor"), "targetActor")),
                    expectRevision(root), decodeTasks(root, "tasks"));
            case "replacePendingQueue" -> new WorldCommand.ReplacePendingQueue(
                    OwnerId.parse(expectStr(field(root, "actorOwner"), "actorOwner")),
                    ActorId.parse(expectStr(field(root, "targetActor"), "targetActor")),
                    expectRevision(root), decodeTasks(root, "tasks"));
            case "cancelQueue" -> new WorldCommand.CancelQueue(
                    OwnerId.parse(expectStr(field(root, "actorOwner"), "actorOwner")),
                    ActorId.parse(expectStr(field(root, "targetActor"), "targetActor")),
                    expectRevision(root), expectCancelActive(root));
            case "setFailurePolicy" -> new WorldCommand.SetFailurePolicy(
                    OwnerId.parse(expectStr(field(root, "actorOwner"), "actorOwner")),
                    ActorId.parse(expectStr(field(root, "targetActor"), "targetActor")),
                    expectRevision(root), expectPolicy(root));
            default -> throw new IllegalArgumentException("unknown command type: \"" + type + "\"");
        };
    }

    private static JsonCodec.JsonObj encodeCommand(WorldCommand command) {
        return switch (command) {
            case WorldCommand.RegisterOwner c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", str("registerOwner")),
                    JsonCodec.JsonObj.entry("externalSeatKey",
                            new JsonCodec.JsonInt(c.externalSeatKey())),
                    JsonCodec.JsonObj.entry("ownerName", str(c.ownerName())),
                    JsonCodec.JsonObj.entry("initialActorLabel", str(c.initialActorLabel()))));
            case WorldCommand.EnqueueTasks c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", str("enqueueTasks")),
                    JsonCodec.JsonObj.entry("actorOwner", str(c.actorOwner().toWire())),
                    JsonCodec.JsonObj.entry("targetActor", str(c.targetActor().toWire())),
                    JsonCodec.JsonObj.entry("expectedRevision", revision(c.expectedRevision())),
                    JsonCodec.JsonObj.entry("tasks", encodeTasks(c.tasks()))));
            case WorldCommand.ReplacePendingQueue c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", str("replacePendingQueue")),
                    JsonCodec.JsonObj.entry("actorOwner", str(c.actorOwner().toWire())),
                    JsonCodec.JsonObj.entry("targetActor", str(c.targetActor().toWire())),
                    JsonCodec.JsonObj.entry("expectedRevision", revision(c.expectedRevision())),
                    JsonCodec.JsonObj.entry("tasks", encodeTasks(c.tasks()))));
            case WorldCommand.CancelQueue c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", str("cancelQueue")),
                    JsonCodec.JsonObj.entry("actorOwner", str(c.actorOwner().toWire())),
                    JsonCodec.JsonObj.entry("targetActor", str(c.targetActor().toWire())),
                    JsonCodec.JsonObj.entry("expectedRevision", revision(c.expectedRevision())),
                    JsonCodec.JsonObj.entry("cancelActive", new JsonCodec.JsonBool(
                            c.cancelActive()))));
            case WorldCommand.SetFailurePolicy c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", str("setFailurePolicy")),
                    JsonCodec.JsonObj.entry("actorOwner", str(c.actorOwner().toWire())),
                    JsonCodec.JsonObj.entry("targetActor", str(c.targetActor().toWire())),
                    JsonCodec.JsonObj.entry("expectedRevision", revision(c.expectedRevision())),
                    JsonCodec.JsonObj.entry("policy", str(c.policy().name()))));
        };
    }

    private static boolean expectCancelActive(JsonCodec.JsonObj root) {
        JsonCodec.JsonValue value = field(root, "cancelActive");
        if (value instanceof JsonCodec.JsonBool bool) {
            return bool.value();
        }
        throw new IllegalArgumentException("command field \"cancelActive\" must be a JSON boolean");
    }

    private static QueueFailurePolicy expectPolicy(JsonCodec.JsonObj root) {
        String name = expectStr(field(root, "policy"), "policy");
        try {
            return QueueFailurePolicy.valueOf(name);
        } catch (IllegalArgumentException unknownPolicy) {
            throw new IllegalArgumentException("unknown queue failure policy: \"" + name + "\"");
        }
    }

    private static JsonCodec.JsonValue revision(Long expectedRevision) {
        return expectedRevision == null
                ? JsonCodec.JsonNull.INSTANCE
                : new JsonCodec.JsonInt(expectedRevision);
    }

    private static JsonCodec.JsonArr encodeTasks(List<TaskSpec> tasks) {
        List<JsonCodec.JsonValue> encoded = new ArrayList<>(tasks.size());
        for (TaskSpec spec : tasks) {
            encoded.add(new JsonCodec.JsonObj(switch (spec) {
                case TaskSpec.Wait(long durationMs, int batches) -> List.of(
                        JsonCodec.JsonObj.entry("type", str("wait")),
                        JsonCodec.JsonObj.entry("durationMs", new JsonCodec.JsonInt(durationMs)),
                        JsonCodec.JsonObj.entry("batches", new JsonCodec.JsonInt(batches)));
                case TaskSpec.Travel(PlaceId destination) -> List.of(
                        JsonCodec.JsonObj.entry("type", str("travel")),
                        JsonCodec.JsonObj.entry("destination", str(destination.toWire())));
            }));
        }
        return new JsonCodec.JsonArr(encoded);
    }

    private static List<TaskSpec> decodeTasks(JsonCodec.JsonObj root, String key) {
        JsonCodec.JsonArr tasks = expectArr(field(root, key), key);
        List<TaskSpec> decoded = new ArrayList<>(tasks.values().size());
        for (JsonCodec.JsonValue value : tasks.values()) {
            JsonCodec.JsonObj spec = expectObj(value, key + " entry");
            String type = expectStr(field(spec, "type"), key + " type");
            decoded.add(switch (type) {
                case "wait" -> new TaskSpec.Wait(
                        expectInt(field(spec, "durationMs"), "wait durationMs"),
                        toIntExact(expectInt(field(spec, "batches"), "wait batches"),
                                "wait batches"));
                case "travel" -> new TaskSpec.Travel(
                        PlaceId.parse(expectStr(field(spec, "destination"), "travel destination")));
                default -> throw new IllegalArgumentException(
                        "unknown task spec type: \"" + type + "\"");
            });
        }
        return List.copyOf(decoded);
    }

    private static Long expectRevision(JsonCodec.JsonObj root) {
        JsonCodec.JsonValue value = field(root, "expectedRevision");
        if (value instanceof JsonCodec.JsonNull) {
            return null;
        }
        return expectInt(value, "expectedRevision");
    }

    private static int toIntExact(long value, String path) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "command field \"" + path + "\" exceeds int range: " + value);
        }
    }

    // ------------------------------------------------------------------ wal records

    /** Encodes {@code record} as canonical compact JSON. */
    public static String encodeWal(WalRecord record) {
        Objects.requireNonNull(record, "record");
        return JsonCodec.write(encodeWalRecord(record));
    }

    /** Decodes a WAL record produced by {@link #encodeWal}; shape errors are rejected. */
    public static WalRecord decodeWal(String json) {
        JsonCodec.JsonObj root = expectObj(JsonCodec.parse(json), "walRecord");
        String kind = expectStr(field(root, "kind"), "walRecord kind");
        return switch (kind) {
            case "clockAdvance" -> new WalRecord.ClockAdvance(
                    expectInt(field(root, "walSeq"), "walSeq"),
                    expectInt(field(root, "admittedMs"), "admittedMs"),
                    expectOptionalHash(root));
            case "commandEntry" -> new WalRecord.CommandEntry(
                    expectInt(field(root, "walSeq"), "walSeq"),
                    expectInt(field(root, "admittedMs"), "admittedMs"),
                    expectInt(field(root, "seatId"), "seatId"),
                    expectStr(field(root, "cmdId"), "cmdId"),
                    decodeCommand(expectObj(field(root, "command"), "command")),
                    expectOptionalHash(root));
            default -> throw new IllegalArgumentException("unknown wal record kind: \"" + kind + "\"");
        };
    }

    private static JsonCodec.JsonObj encodeWalRecord(WalRecord record) {
        return switch (record) {
            case WalRecord.ClockAdvance c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("kind", str("clockAdvance")),
                    JsonCodec.JsonObj.entry("walSeq", new JsonCodec.JsonInt(c.walSeq())),
                    JsonCodec.JsonObj.entry("admittedMs", new JsonCodec.JsonInt(c.admittedMs())),
                    JsonCodec.JsonObj.entry("worldHash", hash(c.worldHash()))));
            case WalRecord.CommandEntry c -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("kind", str("commandEntry")),
                    JsonCodec.JsonObj.entry("walSeq", new JsonCodec.JsonInt(c.walSeq())),
                    JsonCodec.JsonObj.entry("admittedMs", new JsonCodec.JsonInt(c.admittedMs())),
                    JsonCodec.JsonObj.entry("seatId", new JsonCodec.JsonInt(c.seatId())),
                    JsonCodec.JsonObj.entry("cmdId", str(c.cmdId())),
                    JsonCodec.JsonObj.entry("command", encodeCommand(c.command())),
                    JsonCodec.JsonObj.entry("worldHash", hash(c.worldHash()))));
        };
    }

    private static JsonCodec.JsonValue hash(Long worldHash) {
        return worldHash == null ? JsonCodec.JsonNull.INSTANCE : new JsonCodec.JsonInt(worldHash);
    }

    private static Long expectOptionalHash(JsonCodec.JsonObj root) {
        JsonCodec.JsonValue value = field(root, "worldHash");
        if (value instanceof JsonCodec.JsonNull) {
            return null;
        }
        return expectInt(value, "worldHash");
    }

    // ------------------------------------------------------------------ helpers

    private static JsonCodec.JsonStr str(String value) {
        return new JsonCodec.JsonStr(value);
    }

    private static JsonCodec.JsonValue field(JsonCodec.JsonObj obj, String key) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value == null) {
            throw new IllegalArgumentException("command JSON is missing field \"" + key + "\"");
        }
        return value;
    }

    private static JsonCodec.JsonObj expectObj(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonObj obj) {
            return obj;
        }
        throw new IllegalArgumentException(
                "command field \"" + path + "\" must be a JSON object");
    }

    private static JsonCodec.JsonArr expectArr(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonArr arr) {
            return arr;
        }
        throw new IllegalArgumentException(
                "command field \"" + path + "\" must be a JSON array");
    }

    private static String expectStr(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonStr str) {
            return str.value();
        }
        throw new IllegalArgumentException(
                "command field \"" + path + "\" must be a JSON string");
    }

    private static long expectInt(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonInt number) {
            return number.value();
        }
        throw new IllegalArgumentException(
                "command field \"" + path + "\" must be a JSON integer");
    }
}
