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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Golden lock on the at-rest event format: every variant's stored bytes and kind are
 * pinned, decode is the exact inverse, and malformed rows are rejected. A change here is
 * a persisted-data format change and needs a migration story.
 */
class EventPayloadCodecTest {

    private static final OwnerId OWNER = new OwnerId(0, 1);
    private static final ActorId ACTOR = new ActorId(0, 1);
    private static final PlaceId PLACE = new PlaceId(1, 1);
    private static final TaskSpec WAIT = new TaskSpec.Wait(2_500L, 3);
    private static final TaskSpec TRAVEL = new TaskSpec.Travel(PLACE);

    private record Golden(WorldEvent event, String kind, String stored) {
    }

    private static final List<Golden> GOLDENS = List.of(
            new Golden(new WorldEvent.OwnerRegistered(OWNER, ACTOR, 1L, 0L, OWNER,
                    "Seat entered the world", "Owner registered"),
                    "owner_registered",
                    "{\"ownerId\":\"own:0:1\",\"initialActorId\":\"act:0:1\","
                            + "\"scopeOwner\":\"own:0:1\"}"),
            new Golden(new WorldEvent.TaskStarted(ACTOR, new ActiveTask(10L, 2_510L, 4L, WAIT, 3),
                    2L, 10L, OWNER, null, "Started wait"),
                    "task_started",
                    "{\"actorId\":\"act:0:1\",\"task\":{\"startedAtMs\":10,\"dueAtMs\":2510,"
                            + "\"heapTicket\":4,\"spec\":{\"type\":\"wait\",\"durationMs\":2500,"
                            + "\"batches\":3},\"batchesLeft\":3},\"scopeOwner\":\"own:0:1\"}"),
            new Golden(new WorldEvent.TaskBatchCompleted(ACTOR, TRAVEL, 0, 3L, 2_510L, OWNER,
                    null, "Completed travel"),
                    "task_batch_completed",
                    "{\"actorId\":\"act:0:1\",\"spec\":{\"type\":\"travel\","
                            + "\"destination\":\"plc:1:1\"},\"batchesLeftAfter\":0,"
                            + "\"scopeOwner\":\"own:0:1\"}"),
            new Golden(new WorldEvent.TaskCancelled(ACTOR, true, 2, 4L, 3_000L, OWNER,
                    null, "Cancelled"),
                    "task_cancelled",
                    "{\"actorId\":\"act:0:1\",\"cancelledActive\":true,"
                            + "\"clearedPendingCount\":2,\"scopeOwner\":\"own:0:1\"}"),
            new Golden(new WorldEvent.TaskFailed(ACTOR, TRAVEL, ErrorCode.NO_ROUTE, 5L, 3_500L,
                    OWNER, null, "Failed travel"),
                    "task_failed",
                    "{\"actorId\":\"act:0:1\",\"spec\":{\"type\":\"travel\","
                            + "\"destination\":\"plc:1:1\"},\"failureCode\":\"NO_ROUTE\","
                            + "\"scopeOwner\":\"own:0:1\"}"),
            new Golden(new WorldEvent.PlaceDiscovered(PLACE, ACTOR, 6L, 3_500L, OWNER,
                    null, "Discovered"),
                    "place_discovered",
                    "{\"placeId\":\"plc:1:1\",\"discoveredByActor\":\"act:0:1\","
                            + "\"scopeOwner\":\"own:0:1\"}"));

    @Test
    void everyVariantEncodesToPinnedBytesAndKind() {
        for (Golden golden : GOLDENS) {
            assertEquals(golden.kind(), EventPayloadCodec.kind(golden.event()));
            assertEquals(golden.stored(), EventPayloadCodec.encode(golden.event()),
                    golden.kind() + " stored form changed");
        }
    }

    @Test
    void decodeIsTheExactInverseOfEncode() {
        for (Golden golden : GOLDENS) {
            WorldEvent e = golden.event();
            assertEquals(e, EventPayloadCodec.decode(golden.kind(), e.cursor(), e.occurredMs(),
                    e.publicSummary(), e.privateSummary(), golden.stored()));
        }
    }

    @Test
    void decodeIgnoresKeyOrderSoJsonbNormalisationIsHarmless() {
        WorldEvent decoded = EventPayloadCodec.decode("place_discovered", 6L, 3_500L, null,
                "Discovered", "{\"scopeOwner\":\"own:0:1\",\"discoveredByActor\":\"act:0:1\","
                        + "\"placeId\":\"plc:1:1\"}");
        assertEquals(GOLDENS.getLast().event(), decoded);
    }

    @Test
    void payloadOmitsScopeOwner() {
        assertEquals("{\"placeId\":\"plc:1:1\",\"discoveredByActor\":\"act:0:1\"}",
                JsonCodec.write(EventPayloadCodec.payload(GOLDENS.getLast().event())));
    }

    @Test
    void malformedRowsAreRejected() {
        String cancelled = "{\"actorId\":\"act:0:1\",\"cancelledActive\":false,"
                + "\"clearedPendingCount\":%s,\"scopeOwner\":\"own:0:1\"}";
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "task_cancelled", 7L, 4_000L, null, "bad",
                cancelled.replace("%s", "5000000000")));
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "task_cancelled", 7L, 4_000L, null, "bad", cancelled.replace("%s", "\"2\"")));
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "task_exploded", 7L, 4_000L, null, "bad", cancelled.replace("%s", "2")));
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "place_discovered", 7L, 4_000L, null, "bad",
                "{\"placeId\":\"plc:1:1\",\"discoveredByActor\":\"act:0:1\"}"));
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "task_failed", 7L, 4_000L, null, "bad",
                "{\"actorId\":\"act:0:1\",\"spec\":{\"type\":\"travel\",\"destination\":"
                        + "\"plc:1:1\"},\"failureCode\":\"MELTED\",\"scopeOwner\":\"own:0:1\"}"));
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "task_batch_completed", 7L, 4_000L, null, "bad",
                "{\"actorId\":\"act:0:1\",\"spec\":{\"type\":\"teleport\"},"
                        + "\"batchesLeftAfter\":0,\"scopeOwner\":\"own:0:1\"}"));
        assertThrows(IllegalArgumentException.class, () -> EventPayloadCodec.decode(
                "task_started", 7L, 4_000L, null, "bad", "[]"));
    }
}
