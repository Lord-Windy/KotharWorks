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
package com.kotharworks.wilds.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.dev.DevPersona;
import com.kotharworks.auth.token.ApiTokenCodec;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.adapters.memory.InMemoryWorldStore;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.state.TaskSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code WorldHost} wire-protocol acceptance: concurrent duplicate-{@code cmdId}
 * exactly-once execution with identical receipts, {@code 409 STALE_STATE} on a
 * stale {@code expectedRevision}, fault-injected store failures leaving RAM and
 * WAL untouched until a clean retry, and a live SSE stream delivering
 * {@code task_started} &rarr; {@code task_batch_completed} in exact cursor order
 * at {@code --time-scale=20}.
 */
@Timeout(120)
class WorldHostProtocolIntegrationTest {

    private WildsServer server;
    private HttpClient http;
    private String base;
    private String sam;
    private InMemoryWorldStore store;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------ rig

    /** A rig with a paused pump (60 s interval) for deterministic protocol tests. */
    private WildsServer pausedRig() throws IOException {
        return rig(60_000L, 1L);
    }

    /** A rig with a fast pump for live-clock tests. */
    private WildsServer rig(long pumpIntervalMs, long timeScale) throws IOException {
        stopServer();
        ServerConfig config = new ServerConfig(0, 1L, timeScale, pumpIntervalMs,
                AuthMode.DEV_LOCAL, "http://127.0.0.1:0", null, null, null, null, null, null,
                100L, 200L);
        store = new InMemoryWorldStore();
        server = new WildsServer(config, Clock.systemUTC(), null, store, null,
                new ApiTokenCodec(new SecureRandom()));
        server.start();
        base = "http://127.0.0.1:" + server.port();
        http = HttpClient.newHttpClient();
        sam = server.devIssuer().mintSignedJwt(DevPersona.SAM);
        return server;
    }

    /** Charters Sam's seat over HTTP and returns the 201 body. */
    private JsonObj charter() throws IOException, InterruptedException {
        HttpResponse<String> response = post("/api/v1/seats",
                "{\"seatName\":\"Sam Seat\",\"controllerKind\":\"HUMAN\"}", sam);
        assertEquals(201, response.statusCode(), response.body());
        return json(response.body());
    }

    private HttpResponse<String> post(String path, String jsonBody, String bearer)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws IOException,
            InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonObj json(String body) {
        return (JsonObj) JsonCodec.parse(body);
    }

    private static long intField(JsonObj obj, String key) {
        return ((JsonInt) obj.getOrNull(key)).value();
    }

    private static String strField(JsonObj obj, String key) {
        return ((JsonStr) obj.getOrNull(key)).value();
    }

    private static String waitTask(int durationMs) {
        return "{\"type\":\"wait\",\"durationMs\":" + durationMs + ",\"batches\":1}";
    }

    private List<WalRecord> wal() {
        return server.worldStore().loadWalAfter(server.worldHost().worldId(), 0);
    }

    // ------------------------------------------------------------------ (a) cmdId idempotency

    @Test
    void duplicateCmdIdTenConcurrentSubmitsExecuteExactlyOnce() throws Exception {
        rig(60_000L, 1L);
        JsonObj seat = charter();
        long seatId = intField(seat, "seatId");
        OwnerId owner = OwnerId.parse(strField(seat, "ownerId"));
        ActorId actor = ActorId.parse(strField(seat, "actorId"));

        UUID cmdId = UUID.randomUUID();
        WorldCommand command = new WorldCommand.EnqueueTasks(owner, actor, null,
                List.of(new TaskSpec.Wait(1_000L, 1)));

        List<Callable<CommandOutcome>> submissions = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            submissions.add(() -> server.worldHost().submit(seatId, cmdId, command));
        }
        List<Future<CommandOutcome>> futures;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = executor.invokeAll(submissions);
        }
        CommandOutcome.Ok reference = null;
        for (Future<CommandOutcome> future : futures) {
            CommandOutcome.Ok outcome = assertInstanceOf(CommandOutcome.Ok.class,
                    future.get(), "every duplicate must reply Ok");
            if (reference == null) {
                reference = outcome;
            } else {
                assertEquals(reference.receipt(), outcome.receipt(),
                        "duplicate submissions must return identical receipts");
            }
        }

        long commandRows = wal().stream()
                .filter(record -> record instanceof WalRecord.CommandEntry entry
                        && entry.cmdId().equals(cmdId.toString()))
                .count();
        assertEquals(1, commandRows, "exactly one WAL row for the cmdId");
        // One execution = enqueue (+1) and WaitMechanic.startPhase2 (+1) => revision 2;
        // a duplicate execution would land on 4.
        assertEquals(2, server.worldHost().view().actor(actor).orElseThrow().revision(),
                "actor revision advanced exactly once");

        long startedCount = store.readEventsSince(server.worldHost().worldId(), seatId, 0, 1_000)
                .stream()
                .filter(event -> event.kind().equals("task_started"))
                .count();
        assertEquals(1, startedCount, "exactly one task_started event stored");
    }

    // ------------------------------------------------------------------ (b) stale revision

    @Test
    void staleExpectedRevisionReturnsConflictWithCurrentRevision() throws Exception {
        rig(60_000L, 1L);
        JsonObj seat = charter();
        String actor = strField(seat, "actorId");

        HttpResponse<String> response = post("/api/v1/commands", "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + actor
                + "\",\"expectedRevision\":99,\"tasks\":[" + waitTask(1_000) + "]}", sam);
        assertEquals(409, response.statusCode(), response.body());
        JsonObj body = json(response.body());
        assertEquals("STALE_STATE", strField(body, "errorCode"));
        assertEquals(0, intField(body, "currentRevision"),
                "the live actor revision is reported for retries");

        assertEquals(1, wal().size(), "rejection writes zero WAL rows (only the chartered "
                + "command is in the WAL)");
    }

    // ------------------------------------------------------------------ (c) store fault injection

    @Test
    void storeFaultLeavesRamUntouchedUntilCleanRetry() throws Exception {
        rig(60_000L, 1L);
        JsonObj seat = charter();
        String actor = strField(seat, "actorId");

        long hashBefore = server.worldHost().worldHash();
        int walBefore = wal().size();
        store.setAppendCommandFault(new IllegalStateException("disk down"));

        UUID cmdId = UUID.randomUUID();
        HttpResponse<String> faulted = post("/api/v1/commands", "{\"cmdId\":\"" + cmdId
                + "\",\"type\":\"enqueue\",\"targetActor\":\"" + actor + "\",\"tasks\":["
                + waitTask(1_000) + "]}", sam);
        assertEquals(503, faulted.statusCode(), faulted.body());
        assertEquals("STORAGE_UNAVAILABLE", strField(json(faulted.body()), "errorCode"));
        assertEquals(hashBefore, server.worldHost().worldHash(),
                "RAM world hash untouched by the failed commit");
        assertEquals(hashBefore, server.worldHost().view().worldHash(),
                "published WorldView untouched by the failed commit");
        assertEquals(walBefore, wal().size(), "no WAL row for the failed commit");

        store.setAppendCommandFault(null);
        HttpResponse<String> retry = post("/api/v1/commands", "{\"cmdId\":\"" + cmdId
                + "\",\"type\":\"enqueue\",\"targetActor\":\"" + actor + "\",\"tasks\":["
                + waitTask(1_000) + "]}", sam);
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals("OK", strField(json(retry.body()), "status"));
        assertEquals(walBefore + 1, wal().size(), "the retry commits exactly one row");
        assertNotEquals(hashBefore, server.worldHost().worldHash(),
                "the successful retry advances the world");
    }

    // ------------------------------------------------------------------ (d) live SSE

    @Test
    @Timeout(30)
    void liveSseStreamDeliversTaskEventsInCursorOrder() throws Exception {
        rig(50L, 20L);
        JsonObj seat = charter();
        String actor = strField(seat, "actorId");

        HttpClient sseClient = HttpClient.newHttpClient();
        HttpResponse<java.io.InputStream> stream = sseClient.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/stream?since=0"))
                        .header("Authorization", "Bearer " + sam)
                        .header("Accept", "text/event-stream")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());

        assertEquals(200, stream.statusCode());
        assertEquals("text/event-stream; charset=utf-8",
                stream.headers().firstValue("Content-Type").orElseThrow().trim());

        BufferedReader reader = new BufferedReader(new InputStreamReader(stream.body(),
                StandardCharsets.UTF_8));

        // Fire the command after the stream is connected (subscribe happens before
        // replay, so every later event lands in the subscriber queue).
        HttpResponse<String> submitted = post("/api/v1/commands", "{\"cmdId\":\""
                + UUID.randomUUID() + "\",\"type\":\"enqueue\",\"targetActor\":\"" + actor
                + "\",\"tasks\":[" + waitTask(1_000) + "]}", sam);
        assertEquals(200, submitted.statusCode(), submitted.body());

        List<String> kinds = new ArrayList<>();
        List<Long> cursors = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("id: ")) {
                continue; // skip keepalive comment lines and frame separators
            }
            long cursor = Long.parseLong(line.substring("id: ".length()).trim());
            String eventLine = reader.readLine();
            assertTrue(eventLine != null && eventLine.startsWith("event: "),
                    "event line expected after id, got: " + eventLine);
            String kind = eventLine.substring("event: ".length()).trim();
            String dataLine = reader.readLine();
            assertTrue(dataLine != null && dataLine.startsWith("data: "), "data line expected");
            JsonObj data = (JsonObj) JsonCodec.parse(dataLine.substring("data: ".length()));
            assertEquals(cursor, ((JsonInt) data.getOrNull("cursor")).value(),
                    "frame id must match the data cursor");
            if (!cursors.isEmpty()) {
                assertTrue(cursor > cursors.getLast(), "cursors strictly ascend");
            }
            cursors.add(cursor);
            kinds.add(kind);
            if (kind.equals("task_batch_completed")) {
                break;
            }
        }

        int startedIndex = kinds.indexOf("task_started");
        int batchIndex = kinds.indexOf("task_batch_completed");
        assertTrue(startedIndex >= 0, "task_started must be delivered, got: " + kinds);
        assertTrue(batchIndex > startedIndex,
                "task_started must precede task_batch_completed, got: " + kinds);
        assertTrue(kinds.contains("owner_registered"), "replay must include the charter event");

        server.close();
        server = null;
        // The poison marker ends the stream: drain any buffered frame tails. The JDK
        // client's ofInputStream signals the abrupt server-side chunked close as an
        // IOException("closed") — that IS the termination proof; a hang trips @Timeout.
        try {
            while (reader.readLine() != null) {
                // drain until the server-side close terminates the stream
            }
        } catch (IOException expectedOnServerClose) {
            // normal: the exchange closed with the poison marker
        }
        reader.close();
    }

    // ------------------------------------------------------------------ checkpoint on close

    @Test
    void closePersistsCheckpointAndBusWakesSubscribers() throws Exception {
        WildsServer running = rig(60_000L, 1L);
        int worldId = running.worldHost().worldId();
        charter();
        var queue = running.eventBus().subscribe(worldId, 1L);
        running.close();
        server = null;
        assertTrue(store.loadLatestCheckpoint(worldId).isPresent(),
                "checkpoint persisted on close");
        var poison = queue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(poison != null && poison.cursor() < 1,
                "subscriber queue must receive the wake marker");
    }
}
