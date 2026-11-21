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

import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.sim.DuePayload;
import com.kotharworks.wilds.sim.EntityTable;
import com.kotharworks.wilds.sim.LazyGauge;
import com.kotharworks.wilds.sim.ScheduledEvent;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.state.OwnerRow;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;
import com.kotharworks.wilds.state.WorldState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic JSON snapshot codec for {@link WorldState}, built on the zero-dependency
 * {@link JsonCodec}. Round-trip is bit-stable: {@code encode(decode(encode(s)))} equals
 * {@code encode(s)}, and the decoded state's {@link WorldHash} equals the original's.
 *
 * <p>Preserves the exact table {@code highWaterMark} and per-slot generations
 * ({@code 0 .. highWaterMark - 1}, including empty slots), {@code nowMs},
 * {@code nextEventSeq}, {@code nextEventCursor}, {@link LazyGauge} anchors, and the
 * {@code (dueAtMs, seq)}-ordered due-event list.
 */
public final class SnapshotCodec {

    private static final String FORMAT = "wilds-snapshot-v1";

    private SnapshotCodec() {
    }

    /** Encodes {@code state} as canonical compact JSON. */
    public static String encode(WorldState state) {
        Objects.requireNonNull(state, "state");
        List<JsonCodec.JsonObj.Entry> root = new ArrayList<>();
        root.add(JsonCodec.JsonObj.entry("format", new JsonCodec.JsonStr(FORMAT)));
        root.add(JsonCodec.JsonObj.entry("seed", new JsonCodec.JsonInt(state.seed().value())));
        root.add(JsonCodec.JsonObj.entry("rulesVersion", new JsonCodec.JsonInt(state.rulesVersion())));
        root.add(JsonCodec.JsonObj.entry("nowMs", new JsonCodec.JsonInt(state.nowMs())));
        root.add(JsonCodec.JsonObj.entry("nextEventSeq", new JsonCodec.JsonInt(state.nextEventSeq())));
        root.add(JsonCodec.JsonObj.entry("nextEventCursor", new JsonCodec.JsonInt(state.nextEventCursor())));
        root.add(JsonCodec.JsonObj.entry("owners", encodeOwnerSlots(state.owners().dumpSlots())));
        root.add(JsonCodec.JsonObj.entry("places", encodePlaceSlots(state.places().dumpSlots())));
        root.add(JsonCodec.JsonObj.entry("actors", encodeActorSlots(state.actors().dumpSlots())));
        root.add(JsonCodec.JsonObj.entry("ownersBySeatKey", encodeSeatKeys(state)));
        root.add(JsonCodec.JsonObj.entry("dueEvents", encodeDueEvents(state)));
        return JsonCodec.write(new JsonCodec.JsonObj(root));
    }

    /** Decodes a snapshot produced by {@link #encode}; shape errors are rejected. */
    public static WorldState decode(String json) {
        JsonCodec.JsonObj root = expectObj(JsonCodec.parse(json), "root");
        String format = expectStr(field(root, "format"), "format");
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("unsupported snapshot format: \"" + format + "\"");
        }
        long seed = expectInt(field(root, "seed"), "seed");
        int rulesVersion = toIntExact(expectInt(field(root, "rulesVersion"), "rulesVersion"), "rulesVersion");
        long nowMs = expectInt(field(root, "nowMs"), "nowMs");
        long nextEventSeq = expectInt(field(root, "nextEventSeq"), "nextEventSeq");
        long nextEventCursor = expectInt(field(root, "nextEventCursor"), "nextEventCursor");

        WorldState state = new WorldState(new WorldSeed(seed), rulesVersion);
        state.restoreCounters(nowMs, nextEventSeq, nextEventCursor);
        state.owners().restoreSlots(decodeOwnerSlots(expectArr(field(root, "owners"), "owners")));
        state.places().restoreSlots(decodePlaceSlots(expectArr(field(root, "places"), "places")));
        state.actors().restoreSlots(decodeActorSlots(expectArr(field(root, "actors"), "actors")));
        state.restoreSeatKeys(decodeSeatKeys(expectArr(field(root, "ownersBySeatKey"), "ownersBySeatKey")));
        state.dueEvents().restoreFrom(decodeDueEvents(expectArr(field(root, "dueEvents"), "dueEvents")));
        return state;
    }

    // ------------------------------------------------------------------ encode

    private static JsonCodec.JsonValue encodeOwnerSlots(List<EntityTable.SlotDump<OwnerId, OwnerRow>> slots) {
        List<JsonCodec.JsonValue> encoded = new ArrayList<>(slots.size());
        for (EntityTable.SlotDump<OwnerId, OwnerRow> slot : slots) {
            List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
            entries.add(JsonCodec.JsonObj.entry("index", new JsonCodec.JsonInt(slot.index())));
            entries.add(JsonCodec.JsonObj.entry("generation", new JsonCodec.JsonInt(slot.generation())));
            entries.add(JsonCodec.JsonObj.entry("row", slot.row() == null
                    ? JsonCodec.JsonNull.INSTANCE
                    : encodeOwner(slot.row())));
            encoded.add(new JsonCodec.JsonObj(entries));
        }
        return new JsonCodec.JsonArr(encoded);
    }

    private static JsonCodec.JsonValue encodeOwner(OwnerRow row) {
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonCodec.JsonObj.entry("id", new JsonCodec.JsonStr(row.id().toWire())));
        entries.add(JsonCodec.JsonObj.entry("externalSeatKey", new JsonCodec.JsonInt(row.externalSeatKey())));
        entries.add(JsonCodec.JsonObj.entry("name", new JsonCodec.JsonStr(row.name())));
        entries.add(JsonCodec.JsonObj.entry("revision", new JsonCodec.JsonInt(row.revision())));
        entries.add(JsonCodec.JsonObj.entry("homePlaceId", new JsonCodec.JsonStr(row.homePlaceId().toWire())));
        List<JsonCodec.JsonValue> discovered = new ArrayList<>(row.discoveredPlaces().size());
        for (PlaceId place : row.discoveredPlaces()) {
            discovered.add(new JsonCodec.JsonStr(place.toWire()));
        }
        entries.add(JsonCodec.JsonObj.entry("discoveredPlaces", new JsonCodec.JsonArr(discovered)));
        return new JsonCodec.JsonObj(entries);
    }

    private static JsonCodec.JsonValue encodePlaceSlots(List<EntityTable.SlotDump<PlaceId, PlaceRow>> slots) {
        List<JsonCodec.JsonValue> encoded = new ArrayList<>(slots.size());
        for (EntityTable.SlotDump<PlaceId, PlaceRow> slot : slots) {
            List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
            entries.add(JsonCodec.JsonObj.entry("index", new JsonCodec.JsonInt(slot.index())));
            entries.add(JsonCodec.JsonObj.entry("generation", new JsonCodec.JsonInt(slot.generation())));
            entries.add(JsonCodec.JsonObj.entry("row", slot.row() == null
                    ? JsonCodec.JsonNull.INSTANCE
                    : encodePlace(slot.row())));
            encoded.add(new JsonCodec.JsonObj(entries));
        }
        return new JsonCodec.JsonArr(encoded);
    }

    private static JsonCodec.JsonValue encodePlace(PlaceRow row) {
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonCodec.JsonObj.entry("id", new JsonCodec.JsonStr(row.id().toWire())));
        entries.add(JsonCodec.JsonObj.entry("kindId", new JsonCodec.JsonStr(row.kindId().slug())));
        entries.add(JsonCodec.JsonObj.entry("label", new JsonCodec.JsonStr(row.label())));
        List<JsonCodec.JsonValue> edges = new ArrayList<>(row.edges().size());
        for (PlaceEdge edge : row.edges()) {
            edges.add(new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("to", new JsonCodec.JsonStr(edge.to().toWire())),
                    JsonCodec.JsonObj.entry("travelDurationMs",
                            new JsonCodec.JsonInt(edge.travelDurationMs())))));
        }
        entries.add(JsonCodec.JsonObj.entry("edges", new JsonCodec.JsonArr(edges)));
        return new JsonCodec.JsonObj(entries);
    }

    private static JsonCodec.JsonValue encodeActorSlots(List<EntityTable.SlotDump<ActorId, ActorRow>> slots) {
        List<JsonCodec.JsonValue> encoded = new ArrayList<>(slots.size());
        for (EntityTable.SlotDump<ActorId, ActorRow> slot : slots) {
            List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
            entries.add(JsonCodec.JsonObj.entry("index", new JsonCodec.JsonInt(slot.index())));
            entries.add(JsonCodec.JsonObj.entry("generation", new JsonCodec.JsonInt(slot.generation())));
            entries.add(JsonCodec.JsonObj.entry("row", slot.row() == null
                    ? JsonCodec.JsonNull.INSTANCE
                    : encodeActor(slot.row())));
            encoded.add(new JsonCodec.JsonObj(entries));
        }
        return new JsonCodec.JsonArr(encoded);
    }

    private static JsonCodec.JsonValue encodeActor(ActorRow row) {
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonCodec.JsonObj.entry("id", new JsonCodec.JsonStr(row.id().toWire())));
        entries.add(JsonCodec.JsonObj.entry("ownerId", new JsonCodec.JsonStr(row.ownerId().toWire())));
        entries.add(JsonCodec.JsonObj.entry("label", new JsonCodec.JsonStr(row.label())));
        entries.add(JsonCodec.JsonObj.entry("revision", new JsonCodec.JsonInt(row.revision())));
        entries.add(JsonCodec.JsonObj.entry("placeId", new JsonCodec.JsonStr(row.placeId().toWire())));
        LazyGauge gauge = row.energyMilli();
        entries.add(JsonCodec.JsonObj.entry("energyMilli", new JsonCodec.JsonObj(List.of(
                JsonCodec.JsonObj.entry("anchorMilli", new JsonCodec.JsonInt(gauge.anchorMilli())),
                JsonCodec.JsonObj.entry("anchorTimeMs", new JsonCodec.JsonInt(gauge.anchorTimeMs())),
                JsonCodec.JsonObj.entry("rateMilliPerSec", new JsonCodec.JsonInt(gauge.rateMilliPerSec())),
                JsonCodec.JsonObj.entry("minMilli", new JsonCodec.JsonInt(gauge.minMilli())),
                JsonCodec.JsonObj.entry("maxMilli", new JsonCodec.JsonInt(gauge.maxMilli()))))));
        entries.add(JsonCodec.JsonObj.entry("activeTask", row.activeTask() == null
                ? JsonCodec.JsonNull.INSTANCE
                : encodeActiveTask(row.activeTask())));
        List<JsonCodec.JsonValue> queue = new ArrayList<>(row.pendingQueue().size());
        for (TaskSpec spec : row.pendingQueue()) {
            queue.add(encodeSpec(spec));
        }
        entries.add(JsonCodec.JsonObj.entry("pendingQueue", new JsonCodec.JsonArr(queue)));
        entries.add(JsonCodec.JsonObj.entry("failPolicy", new JsonCodec.JsonStr(row.failPolicy().name())));
        return new JsonCodec.JsonObj(entries);
    }

    private static JsonCodec.JsonValue encodeActiveTask(ActiveTask task) {
        List<JsonCodec.JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonCodec.JsonObj.entry("startedAtMs", new JsonCodec.JsonInt(task.startedAtMs())));
        entries.add(JsonCodec.JsonObj.entry("dueAtMs", new JsonCodec.JsonInt(task.dueAtMs())));
        entries.add(JsonCodec.JsonObj.entry("heapTicket", new JsonCodec.JsonInt(task.heapTicket())));
        entries.add(JsonCodec.JsonObj.entry("spec", encodeSpec(task.spec())));
        entries.add(JsonCodec.JsonObj.entry("batchesLeft", new JsonCodec.JsonInt(task.batchesLeft())));
        return new JsonCodec.JsonObj(entries);
    }

    private static JsonCodec.JsonValue encodeSpec(TaskSpec spec) {
        return switch (spec) {
            case TaskSpec.Wait(long durationMs, int batches) -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr("wait")),
                    JsonCodec.JsonObj.entry("durationMs", new JsonCodec.JsonInt(durationMs)),
                    JsonCodec.JsonObj.entry("batches", new JsonCodec.JsonInt(batches))));
            case TaskSpec.Travel(PlaceId destination) -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr("travel")),
                    JsonCodec.JsonObj.entry("destination", new JsonCodec.JsonStr(destination.toWire()))));
        };
    }

    private static JsonCodec.JsonValue encodeSeatKeys(WorldState state) {
        List<JsonCodec.JsonValue> encoded = new ArrayList<>(state.ownersBySeatKey().size());
        for (var entry : state.ownersBySeatKey().entrySet()) {
            encoded.add(new JsonCodec.JsonArr(List.of(
                    new JsonCodec.JsonInt(entry.getKey()),
                    new JsonCodec.JsonStr(entry.getValue().toWire()))));
        }
        return new JsonCodec.JsonArr(encoded);
    }

    private static JsonCodec.JsonValue encodeDueEvents(WorldState state) {
        List<ScheduledEvent> events = state.dueEvents().snapshotOrderedList();
        List<JsonCodec.JsonValue> encoded = new ArrayList<>(events.size());
        for (ScheduledEvent event : events) {
            encoded.add(new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("dueAtMs", new JsonCodec.JsonInt(event.dueAtMs())),
                    JsonCodec.JsonObj.entry("seq", new JsonCodec.JsonInt(event.seq())),
                    JsonCodec.JsonObj.entry("cancellationToken",
                            new JsonCodec.JsonInt(event.cancellationToken())),
                    JsonCodec.JsonObj.entry("payload", encodePayload(event.payload())))));
        }
        return new JsonCodec.JsonArr(encoded);
    }

    private static JsonCodec.JsonValue encodePayload(DuePayload payload) {
        return switch (payload) {
            case DuePayload.CompleteActorTask(ActorId actorId) -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr("completeActorTask")),
                    JsonCodec.JsonObj.entry("actorId", new JsonCodec.JsonStr(actorId.toWire()))));
            case DuePayload.SystemPulse(RuleId pulseId) -> new JsonCodec.JsonObj(List.of(
                    JsonCodec.JsonObj.entry("type", new JsonCodec.JsonStr("systemPulse")),
                    JsonCodec.JsonObj.entry("pulseId", new JsonCodec.JsonStr(pulseId.slug()))));
        };
    }

    // ------------------------------------------------------------------ decode

    private static List<EntityTable.SlotDump<OwnerId, OwnerRow>> decodeOwnerSlots(JsonCodec.JsonArr slots) {
        List<EntityTable.SlotDump<OwnerId, OwnerRow>> dumps = new ArrayList<>(slots.values().size());
        for (JsonCodec.JsonValue slotValue : slots.values()) {
            JsonCodec.JsonObj slot = expectObj(slotValue, "owners slot");
            int index = toIntExact(expectInt(field(slot, "index"), "owners slot index"), "owners slot index");
            int generation = toIntExact(expectInt(field(slot, "generation"), "owners slot generation"),
                    "owners slot generation");
            JsonCodec.JsonValue rowValue = field(slot, "row");
            OwnerRow row = rowValue instanceof JsonCodec.JsonNull
                    ? null
                    : decodeOwner(expectObj(rowValue, "owners slot row"));
            dumps.add(new EntityTable.SlotDump<>(index, generation, row));
        }
        return dumps;
    }

    private static OwnerRow decodeOwner(JsonCodec.JsonObj row) {
        OwnerId id = OwnerId.parse(expectStr(field(row, "id"), "owner id"));
        long externalSeatKey = expectInt(field(row, "externalSeatKey"), "owner externalSeatKey");
        String name = expectStr(field(row, "name"), "owner name");
        long revision = expectInt(field(row, "revision"), "owner revision");
        PlaceId homePlaceId = PlaceId.parse(expectStr(field(row, "homePlaceId"), "owner homePlaceId"));
        JsonCodec.JsonArr discovered = expectArr(field(row, "discoveredPlaces"), "owner discoveredPlaces");
        List<PlaceId> places = new ArrayList<>(discovered.values().size());
        for (JsonCodec.JsonValue wire : discovered.values()) {
            places.add(PlaceId.parse(expectStr(wire, "owner discoveredPlaces entry")));
        }
        return new OwnerRow(id, externalSeatKey, name, revision, homePlaceId, places);
    }

    private static List<EntityTable.SlotDump<PlaceId, PlaceRow>> decodePlaceSlots(JsonCodec.JsonArr slots) {
        List<EntityTable.SlotDump<PlaceId, PlaceRow>> dumps = new ArrayList<>(slots.values().size());
        for (JsonCodec.JsonValue slotValue : slots.values()) {
            JsonCodec.JsonObj slot = expectObj(slotValue, "places slot");
            int index = toIntExact(expectInt(field(slot, "index"), "places slot index"), "places slot index");
            int generation = toIntExact(expectInt(field(slot, "generation"), "places slot generation"),
                    "places slot generation");
            JsonCodec.JsonValue rowValue = field(slot, "row");
            PlaceRow row = rowValue instanceof JsonCodec.JsonNull
                    ? null
                    : decodePlace(expectObj(rowValue, "places slot row"));
            dumps.add(new EntityTable.SlotDump<>(index, generation, row));
        }
        return dumps;
    }

    private static PlaceRow decodePlace(JsonCodec.JsonObj row) {
        PlaceId id = PlaceId.parse(expectStr(field(row, "id"), "place id"));
        RuleId kindId = new RuleId(expectStr(field(row, "kindId"), "place kindId"));
        String label = expectStr(field(row, "label"), "place label");
        JsonCodec.JsonArr edges = expectArr(field(row, "edges"), "place edges");
        List<PlaceEdge> decodedEdges = new ArrayList<>(edges.values().size());
        for (JsonCodec.JsonValue edgeValue : edges.values()) {
            JsonCodec.JsonObj edge = expectObj(edgeValue, "place edge");
            PlaceId to = PlaceId.parse(expectStr(field(edge, "to"), "place edge to"));
            long travelDurationMs = expectInt(field(edge, "travelDurationMs"), "place edge travelDurationMs");
            decodedEdges.add(new PlaceEdge(to, travelDurationMs));
        }
        return new PlaceRow(id, kindId, label, decodedEdges);
    }

    private static List<EntityTable.SlotDump<ActorId, ActorRow>> decodeActorSlots(JsonCodec.JsonArr slots) {
        List<EntityTable.SlotDump<ActorId, ActorRow>> dumps = new ArrayList<>(slots.values().size());
        for (JsonCodec.JsonValue slotValue : slots.values()) {
            JsonCodec.JsonObj slot = expectObj(slotValue, "actors slot");
            int index = toIntExact(expectInt(field(slot, "index"), "actors slot index"), "actors slot index");
            int generation = toIntExact(expectInt(field(slot, "generation"), "actors slot generation"),
                    "actors slot generation");
            JsonCodec.JsonValue rowValue = field(slot, "row");
            ActorRow row = rowValue instanceof JsonCodec.JsonNull
                    ? null
                    : decodeActor(expectObj(rowValue, "actors slot row"));
            dumps.add(new EntityTable.SlotDump<>(index, generation, row));
        }
        return dumps;
    }

    private static ActorRow decodeActor(JsonCodec.JsonObj row) {
        ActorId id = ActorId.parse(expectStr(field(row, "id"), "actor id"));
        OwnerId ownerId = OwnerId.parse(expectStr(field(row, "ownerId"), "actor ownerId"));
        String label = expectStr(field(row, "label"), "actor label");
        long revision = expectInt(field(row, "revision"), "actor revision");
        PlaceId placeId = PlaceId.parse(expectStr(field(row, "placeId"), "actor placeId"));
        JsonCodec.JsonObj gauge = expectObj(field(row, "energyMilli"), "actor energyMilli");
        LazyGauge energyMilli = new LazyGauge(
                expectInt(field(gauge, "anchorMilli"), "actor energyMilli anchorMilli"),
                expectInt(field(gauge, "anchorTimeMs"), "actor energyMilli anchorTimeMs"),
                expectInt(field(gauge, "rateMilliPerSec"), "actor energyMilli rateMilliPerSec"),
                expectInt(field(gauge, "minMilli"), "actor energyMilli minMilli"),
                expectInt(field(gauge, "maxMilli"), "actor energyMilli maxMilli"));
        JsonCodec.JsonValue activeTaskValue = field(row, "activeTask");
        ActiveTask activeTask = activeTaskValue instanceof JsonCodec.JsonNull
                ? null
                : decodeActiveTask(expectObj(activeTaskValue, "actor activeTask"));
        JsonCodec.JsonArr queue = expectArr(field(row, "pendingQueue"), "actor pendingQueue");
        List<TaskSpec> pendingQueue = new ArrayList<>(queue.values().size());
        for (JsonCodec.JsonValue specValue : queue.values()) {
            pendingQueue.add(decodeSpec(expectObj(specValue, "actor pendingQueue spec")));
        }
        QueueFailurePolicy failPolicy =
                QueueFailurePolicy.valueOf(expectStr(field(row, "failPolicy"), "actor failPolicy"));
        return new ActorRow(id, ownerId, label, revision, placeId, energyMilli, activeTask,
                pendingQueue, failPolicy);
    }

    private static ActiveTask decodeActiveTask(JsonCodec.JsonObj task) {
        long startedAtMs = expectInt(field(task, "startedAtMs"), "activeTask startedAtMs");
        long dueAtMs = expectInt(field(task, "dueAtMs"), "activeTask dueAtMs");
        long heapTicket = expectInt(field(task, "heapTicket"), "activeTask heapTicket");
        TaskSpec spec = decodeSpec(expectObj(field(task, "spec"), "activeTask spec"));
        int batchesLeft = toIntExact(expectInt(field(task, "batchesLeft"), "activeTask batchesLeft"),
                "activeTask batchesLeft");
        return new ActiveTask(startedAtMs, dueAtMs, heapTicket, spec, batchesLeft);
    }

    private static TaskSpec decodeSpec(JsonCodec.JsonObj spec) {
        String type = expectStr(field(spec, "type"), "spec type");
        return switch (type) {
            case "wait" -> new TaskSpec.Wait(
                    expectInt(field(spec, "durationMs"), "wait spec durationMs"),
                    toIntExact(expectInt(field(spec, "batches"), "wait spec batches"), "wait spec batches"));
            case "travel" -> new TaskSpec.Travel(
                    PlaceId.parse(expectStr(field(spec, "destination"), "travel spec destination")));
            default -> throw new IllegalArgumentException("unknown spec type: \"" + type + "\"");
        };
    }

    private static TreeMap<Long, OwnerId> decodeSeatKeys(JsonCodec.JsonArr entries) {
        TreeMap<Long, OwnerId> seatKeys = new TreeMap<>();
        for (JsonCodec.JsonValue entryValue : entries.values()) {
            JsonCodec.JsonArr entry = expectArr(entryValue, "ownersBySeatKey entry");
            if (entry.values().size() != 2) {
                throw new IllegalArgumentException("ownersBySeatKey entry must hold [key, ownerWire]");
            }
            long key = expectInt(entry.values().get(0), "ownersBySeatKey key");
            OwnerId owner = OwnerId.parse(expectStr(entry.values().get(1), "ownersBySeatKey owner"));
            seatKeys.put(key, owner);
        }
        return seatKeys;
    }

    private static List<ScheduledEvent> decodeDueEvents(JsonCodec.JsonArr events) {
        List<ScheduledEvent> decoded = new ArrayList<>(events.values().size());
        for (JsonCodec.JsonValue eventValue : events.values()) {
            JsonCodec.JsonObj event = expectObj(eventValue, "dueEvents entry");
            long dueAtMs = expectInt(field(event, "dueAtMs"), "dueEvent dueAtMs");
            long seq = expectInt(field(event, "seq"), "dueEvent seq");
            long cancellationToken = expectInt(field(event, "cancellationToken"), "dueEvent cancellationToken");
            JsonCodec.JsonObj payload = expectObj(field(event, "payload"), "dueEvent payload");
            String type = expectStr(field(payload, "type"), "dueEvent payload type");
            DuePayload decodedPayload = switch (type) {
                case "completeActorTask" -> new DuePayload.CompleteActorTask(
                        ActorId.parse(expectStr(field(payload, "actorId"), "dueEvent actorId")));
                case "systemPulse" -> new DuePayload.SystemPulse(
                        new RuleId(expectStr(field(payload, "pulseId"), "dueEvent pulseId")));
                default -> throw new IllegalArgumentException("unknown dueEvent payload type: \"" + type + "\"");
            };
            decoded.add(new ScheduledEvent(dueAtMs, seq, cancellationToken, decodedPayload));
        }
        return decoded;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonCodec.JsonValue field(JsonCodec.JsonObj obj, String key) {
        JsonCodec.JsonValue value = obj.getOrNull(key);
        if (value == null) {
            throw new IllegalArgumentException("snapshot is missing field \"" + key + "\"");
        }
        return value;
    }

    private static JsonCodec.JsonObj expectObj(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonObj obj) {
            return obj;
        }
        throw shapeError(path, value, "object");
    }

    private static JsonCodec.JsonArr expectArr(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonArr arr) {
            return arr;
        }
        throw shapeError(path, value, "array");
    }

    private static String expectStr(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonStr str) {
            return str.value();
        }
        throw shapeError(path, value, "string");
    }

    private static long expectInt(JsonCodec.JsonValue value, String path) {
        if (value instanceof JsonCodec.JsonInt number) {
            return number.value();
        }
        throw shapeError(path, value, "integer");
    }

    private static int toIntExact(long value, String path) {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("snapshot field \"" + path + "\" exceeds int range: " + value);
        }
    }

    private static IllegalArgumentException shapeError(String path, JsonCodec.JsonValue value, String expected) {
        String actual = value == null ? "null" : value.getClass().getSimpleName();
        return new IllegalArgumentException(
                "snapshot field \"" + path + "\" must be a JSON " + expected + " but was " + actual);
    }
}
