/*
 * Copyright 2026 Samuel "Lord-Windy" Brown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use it except in compliance with the License.
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

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.server.domain.SeatAuthorizationException;

/**
 * Parses the {@code POST /api/v1/commands} envelope. The server injects the
 * caller's authorized {@code OwnerId} — a raw {@code actorOwner} field is
 * rejected, and any flavor of {@code register_owner} is refused outright (seat
 * registration flows only through {@code POST /api/v1/seats}). Malformed JSON,
 * ids, or policies raise {@link IllegalArgumentException} (rendered as
 * {@code 400 INVALID_ARGUMENT} by the route).
 */
public final class CommandRequestCodec {

    /** One parsed command: the idempotency key plus the engine command. */
    public record ParsedCommand(UUID cmdId, WorldCommand command) {

        public ParsedCommand {
            Objects.requireNonNull(cmdId, "cmdId");
            Objects.requireNonNull(command, "command");
        }
    }

    private CommandRequestCodec() {
    }

    /**
     * Parses the request body against the caller's authorized owner.
     *
     * @param body        raw request body
     * @param actorOwner  the server-injected owner (never client-supplied)
     * @throws SeatAuthorizationException on any {@code register_owner} flavor
     * @throws IllegalArgumentException  on malformed structure or ids
     */
    public static ParsedCommand parse(byte[] body, OwnerId actorOwner) {
        Objects.requireNonNull(actorOwner, "actorOwner");
        JsonCodec.JsonObj root = ApiJson.parseObject(body);
        if (root.getOrNull("actorOwner") != null || root.getOrNull("ownerId") != null) {
            throw new IllegalArgumentException(
                    "field \"actorOwner\" is server-injected and must not appear in requests");
        }
        UUID cmdId = ApiJson.requireUuid(root, "cmdId");
        String type = ApiJson.requireString(root, "type");
        return switch (type) {
            case "register_owner", "registerOwner" -> throw new SeatAuthorizationException(403,
                    "FORBIDDEN", "seat registration flows only through POST /api/v1/seats");
            case "enqueue" -> new ParsedCommand(cmdId, new WorldCommand.EnqueueTasks(actorOwner,
                    targetActor(root), expectedRevision(root), tasks(root)));
            case "replace_queue" -> new ParsedCommand(cmdId,
                    new WorldCommand.ReplacePendingQueue(actorOwner, targetActor(root),
                            expectedRevision(root), tasks(root)));
            case "cancel_queue" -> new ParsedCommand(cmdId, new WorldCommand.CancelQueue(
                    actorOwner, targetActor(root), expectedRevision(root),
                    ApiJson.optBool(root, "cancelActive", false)));
            case "set_fail_policy" -> new ParsedCommand(cmdId, new WorldCommand.SetFailurePolicy(
                    actorOwner, targetActor(root), expectedRevision(root), policy(root)));
            default -> throw new IllegalArgumentException(
                    "unknown command type: \"" + type + "\"");
        };
    }

    private static ActorId targetActor(JsonCodec.JsonObj root) {
        String wire = ApiJson.requireString(root, "targetActor");
        return ActorId.parse(wire);
    }

    private static Long expectedRevision(JsonCodec.JsonObj root) {
        return ApiJson.optLong(root, "expectedRevision");
    }

    private static List<TaskSpec> tasks(JsonCodec.JsonObj root) {
        JsonCodec.JsonValue value = root.getOrNull("tasks");
        if (value == null) {
            throw new IllegalArgumentException("field \"tasks\" is required for this type");
        }
        return EventCodec.decodeTasks(value, "tasks");
    }

    private static QueueFailurePolicy policy(JsonCodec.JsonObj root) {
        String name = ApiJson.requireString(root, "policy");
        try {
            return QueueFailurePolicy.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "unknown queue failure policy: \"" + name + "\"");
        }
    }
}
