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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Command/WAL codec round-trip property: {@code decode(encode(x)).equals(x)} for every
 * variant, canonical byte-stable re-encoding, and rejection of malformed JSON, unknown
 * type tags, and floating-point numbers.
 */
class CommandCodecTest {

    @Test
    void registerOwnerRoundTrips() {
        WorldCommand command = new WorldCommand.RegisterOwner(7L, "Ash", "Ash's Scout");
        assertEquals(command, CommandCodec.decode(CommandCodec.encode(command)));
    }

    @Test
    void enqueueTasksRoundTripsWithNullAndNonNullRevision() {
        WorldCommand nullRevision = new WorldCommand.EnqueueTasks(new OwnerId(0, 1),
                new ActorId(0, 1), null,
                List.of(new TaskSpec.Wait(2_000, 3), new TaskSpec.Travel(new PlaceId(1, 1))));
        assertEquals(nullRevision, CommandCodec.decode(CommandCodec.encode(nullRevision)));

        WorldCommand pinnedRevision = new WorldCommand.EnqueueTasks(new OwnerId(0, 1),
                new ActorId(0, 1), 12L, List.of(new TaskSpec.Travel(new PlaceId(2, 1))));
        assertEquals(pinnedRevision, CommandCodec.decode(CommandCodec.encode(pinnedRevision)));
    }

    @Test
    void replacePendingQueueRoundTrips() {
        WorldCommand command = new WorldCommand.ReplacePendingQueue(new OwnerId(1, 2),
                new ActorId(2, 3), 4L, List.of(new TaskSpec.Wait(60_000, 10)));
        assertEquals(command, CommandCodec.decode(CommandCodec.encode(command)));
    }

    @Test
    void cancelQueueRoundTripsBothFlags() {
        for (boolean cancelActive : new boolean[] {true, false}) {
            WorldCommand command = new WorldCommand.CancelQueue(new OwnerId(0, 1),
                    new ActorId(0, 1), null, cancelActive);
            assertEquals(command, CommandCodec.decode(CommandCodec.encode(command)));
        }
    }

    @Test
    void setFailurePolicyRoundTripsBothPolicies() {
        for (QueueFailurePolicy policy : QueueFailurePolicy.values()) {
            WorldCommand command = new WorldCommand.SetFailurePolicy(new OwnerId(0, 1),
                    new ActorId(0, 1), 9L, policy);
            assertEquals(command, CommandCodec.decode(CommandCodec.encode(command)));
        }
    }

    @Test
    void encodingIsCanonicalAndByteStable() {
        WorldCommand command = new WorldCommand.EnqueueTasks(new OwnerId(0, 1),
                new ActorId(0, 1), null,
                List.of(new TaskSpec.Wait(2_000, 3), new TaskSpec.Travel(new PlaceId(1, 1))));
        String encoded = CommandCodec.encode(command);
        assertEquals(encoded, CommandCodec.encode(CommandCodec.decode(encoded)));
        assertTrue(encoded.contains("\"type\":\"enqueueTasks\""));
        assertTrue(encoded.contains("\"expectedRevision\":null"));
        assertTrue(encoded.contains("\"destination\":\"plc:1:1\""));
        assertTrue(encoded.startsWith(
                "{\"type\":\"enqueueTasks\",\"actorOwner\":\"own:0:1\",\"targetActor\":\"act:0:1\""),
                "key order must be fixed and compact");
    }

    @Test
    void walRecordsRoundTripAndExposeSharedAccessors() {
        WalRecord advance = new WalRecord.ClockAdvance(3L, 45_000L, -8_123L);
        assertEquals(3L, advance.walSeq());
        assertEquals(45_000L, advance.admittedMs());
        assertEquals(-8_123L, advance.worldHash());
        assertEquals(advance, CommandCodec.decodeWal(CommandCodec.encodeWal(advance)));

        WalRecord bare = new WalRecord.ClockAdvance(1L, 0L, null);
        assertEquals(bare, CommandCodec.decodeWal(CommandCodec.encodeWal(bare)));

        WalRecord entry = new WalRecord.CommandEntry(2L, 1_000L, 7L, "cmd-0002",
                new WorldCommand.RegisterOwner(7L, "Ash", "Scout"), null);
        assertEquals(2L, entry.walSeq());
        assertEquals(1_000L, entry.admittedMs());
        assertEquals(entry, CommandCodec.decodeWal(CommandCodec.encodeWal(entry)));

        WalRecord hashed = new WalRecord.CommandEntry(4L, 50_000L, 7L, "cmd-0004",
                new WorldCommand.CancelQueue(new OwnerId(0, 1), new ActorId(0, 1), 3L, true),
                123_456_789L);
        assertEquals(123_456_789L, hashed.worldHash());
        assertEquals(hashed, CommandCodec.decodeWal(CommandCodec.encodeWal(hashed)));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode("not json"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode("{}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(
                "{\"type\":\"enqueueTasks\",\"actorOwner\":\"own:0:1\"}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(
                "{\"type\":\"enqueueTasks\",\"actorOwner\":\"own:0:1\",\"targetActor\":\"act:0:1\","
                        + "\"expectedRevision\":null,\"tasks\":[{\"type\":\"wait\",\"batches\":1}]}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(
                "{\"type\":\"weird\"}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(
                "{\"type\":\"registerOwner\",\"externalSeatKey\":1.5,\"ownerName\":\"a\","
                        + "\"initialActorLabel\":\"b\"}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(
                "{\"type\":\"setFailurePolicy\",\"actorOwner\":\"own:0:1\","
                        + "\"targetActor\":\"act:0:1\",\"expectedRevision\":null,"
                        + "\"policy\":\"NO_SUCH_POLICY\"}"));
    }

    @Test
    void malformedWalJsonIsRejected() {
        IllegalArgumentException nonObj = assertThrows(IllegalArgumentException.class,
                () -> CommandCodec.decodeWal("null"));
        assertTrue(nonObj.getMessage().startsWith("WAL field"), nonObj.getMessage());
        IllegalArgumentException missingKind = assertThrows(IllegalArgumentException.class,
                () -> CommandCodec.decodeWal("{}"));
        assertTrue(missingKind.getMessage().startsWith("WAL JSON"), missingKind.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decodeWal(
                "{\"kind\":\"clockAdvance\",\"walSeq\":0,\"admittedMs\":0,\"worldHash\":null}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decodeWal(
                "{\"kind\":\"clockAdvance\",\"walSeq\":1,\"admittedMs\":-1,\"worldHash\":null}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decodeWal(
                "{\"kind\":\"commandEntry\",\"walSeq\":1,\"admittedMs\":0,\"seatId\":1,"
                        + "\"cmdId\":\"  \",\"command\":{\"type\":\"registerOwner\","
                        + "\"externalSeatKey\":1,\"ownerName\":\"a\",\"initialActorLabel\":\"b\"},"
                        + "\"worldHash\":null}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decodeWal(
                "{\"kind\":\"commandEntry\",\"walSeq\":1,\"admittedMs\":0,\"seatId\":1,"
                        + "\"cmdId\":\"x\",\"command\":{\"type\":\"nope\"},\"worldHash\":null}"));
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decodeWal(
                "{\"kind\":\"clockAdvance\",\"walSeq\":1,\"admittedMs\":1e3,\"worldHash\":null}"));
    }

    @Test
    void walHeaderRoundTripsAndValidatesShape() {
        WalHeader header = new WalHeader(Long.MIN_VALUE, 1, 1);
        String encoded = header.encode();
        assertEquals("{\"type\":\"WAL_HEADER\",\"worldSeed\":-9223372036854775808,"
                + "\"rulesVersion\":1,\"engineVersion\":1}", encoded);
        assertEquals(header, WalHeader.decode(encoded));
        JsonCodec.JsonObj parsedObj = (JsonCodec.JsonObj) JsonCodec.parse(encoded);
        assertTrue(WalHeader.isHeader(parsedObj));
        assertEquals(header, WalHeader.decode(parsedObj));

        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode("[]"));
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode("{}"));
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode(
                "{\"type\":\"WAL_HEADER\",\"worldSeed\":\"bad\",\"rulesVersion\":1,\"engineVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode(
                "{\"type\":\"WAL_HEADER\",\"worldSeed\":1,\"rulesVersion\":2147483648,\"engineVersion\":1}"));
    }
}
