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
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.adapters.memory.InMemoryWorldStore;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.WorldHost;
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
                100L, 200L, "127.0.0.1", ServerConfig.StorageMode.MEMORY, null, null, null,
                300_000L);
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

    @Test
    void duplicateCmdIdTenConcurrentHttpPostsReturnIdenticalReceipts() throws Exception {
        rig(60_000L, 1L);
        JsonObj seat = charter();
        String actor = strField(seat, "actorId");
        UUID cmdId = UUID.randomUUID();
        String body = "{\"cmdId\":\"" + cmdId + "\",\"type\":\"enqueue\",\"targetActor\":\""
                + actor + "\",\"tasks\":[" + waitTask(1_000) + "]}";

        List<Callable<HttpResponse<String>>> posts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            posts.add(() -> post("/api/v1/commands", body, sam));
        }
        List<Future<HttpResponse<String>>> futures;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = executor.invokeAll(posts);
        }
        String reference = null;
        for (Future<HttpResponse<String>> future : futures) {
            HttpResponse<String> response = future.get();
            assertEquals(200, response.statusCode(), response.body());
            String receipt = JsonCodec.write(json(response.body()).getOrNull("receipt"));
            if (reference == null) {
                reference = receipt;
            } else {
                assertEquals(reference, receipt, "duplicate POSTs must return identical receipts");
            }
        }

        long commandRows = wal().stream()
                .filter(record -> record instanceof WalRecord.CommandEntry entry
                        && entry.cmdId().equals(cmdId.toString()))
                .count();
        assertEquals(1, commandRows, "exactly one WAL row for the cmdId");
        assertEquals(2, server.worldHost().view().actor(ActorId.parse(actor)).orElseThrow()
                .revision(), "actor revision advanced exactly once");
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
        server.worldHost().writeCheckpointNow();
        assertEquals(hashBefore,
                store.loadLatestCheckpoint(server.worldHost().worldId()).orElseThrow().worldHash(),
                "engine.worldHash() (persisted by an on-writer checkpoint) untouched by the "
                        + "failed commit");
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

    @Test
    void currentLogicalNowMsExtrapolatesBeyondPublishedViewWithoutAdvancingIt() {
        for (long scale : new long[] {1L, 10L}) {
            long[] wallNanos = new long[] {1_000_000_000L};
            WorldHost host = new WorldHost(new InMemoryWorldStore(), new InMemoryEventBus(),
                    () -> wallNanos[0], 1L, scale, 3_600_000L, Long.MAX_VALUE);
            try {
                long base = host.view().nowMs();
                wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
                assertEquals(base, host.view().nowMs());
                assertEquals(base + 2_000L * scale, host.currentLogicalNowMs());
            } finally {
                host.close();
            }
        }
    }

    // ------------------------------------------------------------------ Pass 07 regressions

    @Test
    void idleWallTimeDoesNotTeleportNewlySubmittedTasksAndCrossSeatCmdIdIsRejected() {
        long[] wallNanos = new long[] {1_000_000_000L};
        InMemoryWorldStore memStore = new InMemoryWorldStore();
        WorldHost host = new WorldHost(memStore, new InMemoryEventBus(), () -> wallNanos[0],
                1L, 1L, 60_000L, Long.MAX_VALUE);
        try {
            assertInstanceOf(CommandOutcome.Ok.class, host.submit(1L, UUID.randomUUID(),
                    new WorldCommand.RegisterOwner(1L, "Alice", "Alice Actor")));
            assertInstanceOf(CommandOutcome.Ok.class, host.submit(2L, UUID.randomUUID(),
                    new WorldCommand.RegisterOwner(2L, "Bob", "Bob Actor")));
            OwnerId aliceOwner = host.view().findOwnerBySeatKey(1L).orElseThrow().id();
            ActorId aliceActor = host.view().actorsOwnedBy(aliceOwner).getFirst().id();
            OwnerId bobOwner = host.view().findOwnerBySeatKey(2L).orElseThrow().id();
            ActorId bobActor = host.view().actorsOwnedBy(bobOwner).getFirst().id();

            // Sit idle for 45 wall-clock seconds (< 60s quiet threshold): ticks do not spam WAL.
            wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            host.tick();

            // Submit a 30-second Wait at t=45s: admittedMs must be 45_000ms, dueAtMs = 75_000ms.
            UUID aliceCmdId = UUID.randomUUID();
            CommandOutcome.Ok ok = assertInstanceOf(CommandOutcome.Ok.class,
                    host.submit(1L, aliceCmdId, new WorldCommand.EnqueueTasks(
                            aliceOwner, aliceActor, null, List.of(new TaskSpec.Wait(30_000L, 1)))));
            assertEquals(45_000L, ok.receipt().admittedMs());

            // 1 second later (t=46s), tick must NOT finish the 30-second task prematurely!
            wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
            host.tick();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (host.view().actor(aliceActor).orElseThrow().activeTask() == null
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(host.view().actor(aliceActor).orElseThrow().activeTask() != null,
                    "30s task queued after 45s idle must still be running 1s later");

            // Seat 2 attempting to replay Seat 1's cmdId is rejected with FORBIDDEN (no oracle).
            CommandOutcome.Rejected crossCmd = assertInstanceOf(CommandOutcome.Rejected.class,
                    host.submit(2L, aliceCmdId, new WorldCommand.EnqueueTasks(
                            bobOwner, bobActor, null, List.of(new TaskSpec.Wait(1_000L, 1)))));
            assertEquals("FORBIDDEN", crossCmd.errorCode());

            // Advancing the remaining 29 seconds (to t=75s) completes the task cleanly.
            wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(29);
            host.tick();
            long finishDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (host.view().actor(aliceActor).orElseThrow().activeTask() != null
                    && System.nanoTime() < finishDeadline) {
                Thread.onSpinWait();
            }
            assertTrue(host.view().actor(aliceActor).orElseThrow().activeTask() == null,
                    "task completes once the full 30s has elapsed");
        } finally {
            host.close();
        }
    }

    @Test
    void pumpWritesClockAdvanceOnlyWhenDueOrQuietThresholdCrossed() {
        long[] wallNanos = new long[] {1_000_000_000L};
        InMemoryWorldStore memStore = new InMemoryWorldStore();
        WorldHost host = new WorldHost(memStore, new InMemoryEventBus(), () -> wallNanos[0],
                1L, 1L, 60_000L, Long.MAX_VALUE);
        try {
            assertInstanceOf(CommandOutcome.Ok.class, host.submit(1L, UUID.randomUUID(),
                    new WorldCommand.RegisterOwner(1L, "Alice", "Alice Actor")));
            OwnerId owner = host.view().findOwnerBySeatKey(1L).orElseThrow().id();
            ActorId actor = host.view().actorsOwnedBy(owner).getFirst().id();
            int worldId = host.worldId();
            long base = clockRows(memStore, worldId);

            wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            host.tick();
            host.writeCheckpointNow();
            assertEquals(base, clockRows(memStore, worldId), "idle ticks below 60s write nothing");

            wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(16);
            host.tick();
            host.writeCheckpointNow();
            assertEquals(base + 1, clockRows(memStore, worldId),
                    "crossing the 60s quiet threshold writes one CLOCK_ADVANCE");
            WalRecord.ClockAdvance quiet = (WalRecord.ClockAdvance) memStore
                    .loadWalAfter(worldId, 0).stream()
                    .filter(r -> r instanceof WalRecord.ClockAdvance).reduce((_, b) -> b)
                    .orElseThrow();
            assertTrue(quiet.admittedMs() >= 60_000L, "anchor at " + quiet.admittedMs());

            assertInstanceOf(CommandOutcome.Ok.class, host.submit(1L, UUID.randomUUID(),
                    new WorldCommand.EnqueueTasks(owner, actor, null,
                            List.of(new TaskSpec.Wait(1_000L, 1)))));
            wallNanos[0] += java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            assertInstanceOf(CommandOutcome.Ok.class, host.submit(1L, UUID.randomUUID(),
                    new WorldCommand.EnqueueTasks(owner, actor, null,
                            List.of(new TaskSpec.Wait(1_000L, 1)))));
            List<WalRecord> records = memStore.loadWalAfter(worldId, 0);
            WalRecord last = records.getLast();
            WalRecord.CommandEntry second = assertInstanceOf(WalRecord.CommandEntry.class, last);
            WalRecord.ClockAdvance anchor = assertInstanceOf(WalRecord.ClockAdvance.class,
                    records.get(records.size() - 2),
                    "due events are anchored by a CLOCK_ADVANCE before the command");
            assertEquals(second.admittedMs(), anchor.admittedMs());
        } finally {
            host.close();
        }
    }

    private static long clockRows(InMemoryWorldStore s, int worldId) {
        return s.loadWalAfter(worldId, 0).stream()
                .filter(r -> r instanceof WalRecord.ClockAdvance).count();
    }

    @Test
    void playerCharterRetryAfter503Recovers_andEmptyEventPollPreservesNextSince() throws Exception {
        rig(60_000L, 1L);
        String alice = server.devIssuer().mintSignedJwt(DevPersona.ALICE);

        // Fault appendCommand during PLAYER Alice's first charter -> 503; retry succeeds!
        store.setAppendCommandFault(new IllegalStateException("transient wal failure"));
        HttpResponse<String> faultedCharter = post("/api/v1/seats",
                "{\"seatName\":\"Alice Hold\",\"controllerKind\":\"HUMAN\"}", alice);
        assertEquals(503, faultedCharter.statusCode(), faultedCharter.body());

        store.setAppendCommandFault(null);
        HttpResponse<String> recoveredCharter = post("/api/v1/seats",
                "{\"seatName\":\"Alice Hold\",\"controllerKind\":\"HUMAN\"}", alice);
        assertEquals(200, recoveredCharter.statusCode(), recoveredCharter.body());
        assertNotEquals("null", recoveredCharter.body());
        assertEquals(200, get("/api/v1/observation", alice).statusCode());

        // Event polling: first page advances nextSince > 0; empty follow-up preserves nextSince!
        HttpResponse<String> page1 = get("/api/v1/events?since=0", alice);
        assertEquals(200, page1.statusCode());
        long cursor1 = intField(json(page1.body()), "nextSince");
        assertTrue(cursor1 >= 1L, "initial registration event advances cursor");

        HttpResponse<String> page2 = get("/api/v1/events?since=" + cursor1, alice);
        assertEquals(200, page2.statusCode());
        assertEquals(cursor1, intField(json(page2.body()), "nextSince"),
                "empty event poll must preserve since cursor rather than resetting to 0");

        HttpResponse<String> pubEmpty = get("/api/v1/public/events?since=" + cursor1, null);
        assertEquals(200, pubEmpty.statusCode());
        assertEquals(cursor1, intField(json(pubEmpty.body()), "nextSince"),
                "empty public event poll must also preserve since cursor");
    }

    @Test
    void preCheckpointCmdIdRetryRebuildsExactReceipt_andSseOverflowRecoversAllCursors()
            throws Exception {
        long[] wallNanos = new long[] {5_000_000_000L};
        InMemoryWorldStore memStore = new InMemoryWorldStore();
        InMemoryEventBus bus = new InMemoryEventBus();
        WorldHost host1 = new WorldHost(memStore, bus, () -> wallNanos[0],
                1L, 1L, 60_000L, Long.MAX_VALUE);
        assertInstanceOf(CommandOutcome.Ok.class, host1.submit(1L, UUID.randomUUID(),
                new WorldCommand.RegisterOwner(1L, "Sam", "Sam Actor")));
        OwnerId owner = host1.view().findOwnerBySeatKey(1L).orElseThrow().id();
        ActorId actor = host1.view().actorsOwnedBy(owner).getFirst().id();

        wallNanos[0] += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);
        UUID cmdId = UUID.randomUUID();
        CommandOutcome.Ok original = assertInstanceOf(CommandOutcome.Ok.class,
                host1.submit(1L, cmdId, new WorldCommand.EnqueueTasks(
                        owner, actor, null, List.of(new TaskSpec.Wait(1_000L, 1)))));
        assertTrue(original.receipt().walSeq() >= 2L);
        assertEquals(250L, original.receipt().admittedMs());
        host1.close(); // writes checkpoint covering walSeq, so next boot replays 0 rows

        WorldHost host2 = new WorldHost(memStore, new InMemoryEventBus(), () -> wallNanos[0],
                1L, 1L, 60_000L, Long.MAX_VALUE);
        try {
            CommandOutcome.Ok retried = assertInstanceOf(CommandOutcome.Ok.class,
                    host2.submit(1L, cmdId, new WorldCommand.EnqueueTasks(
                            owner, actor, null, List.of(new TaskSpec.Wait(1_000L, 1)))));
            assertEquals(original.receipt().walSeq(), retried.receipt().walSeq(),
                    "pre-checkpoint retry must recover exact walSeq from command_wal");
            assertEquals(original.receipt().admittedMs(), retried.receipt().admittedMs(),
                    "pre-checkpoint retry must recover exact admittedMs from command_wal");
        } finally {
            host2.close();
        }

        // SSE overflow: burst > 1024 events into a live stream before the reader drains;
        // the OVERFLOW marker triggers catchUp() so zero cursors are lost.
        rig(60_000L, 1L);
        JsonObj seat = charter();
        long seatId = intField(seat, "seatId");
        int worldId = server.worldHost().worldId();

        HttpClient sseClient = HttpClient.newHttpClient();
        HttpResponse<java.io.InputStream> stream = sseClient.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/stream?since=0"))
                        .header("Authorization", "Bearer " + sam)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());

        List<com.kotharworks.wilds.server.ports.StoredWorldEvent> burst = new ArrayList<>(1_100);
        for (long c = 2L; c <= 1_101L; c++) {
            burst.add(com.kotharworks.wilds.server.ports.StoredWorldEvent.full(
                    c, c * 10L, "task_started", seatId, null, "burst-" + c, null));
        }
        store.appendEvents(worldId, burst);
        server.eventBus().publish(worldId, burst); // overflows 1024-capacity subscriber queue

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream.body(), StandardCharsets.UTF_8))) {
            long expectedCursor = 1L; // cursor 1 = owner_registered, 2..1101 = burst
            while (expectedCursor <= 1_101L) {
                String line = reader.readLine();
                assertTrue(line != null, "stream ended early at cursor " + expectedCursor);
                if (!line.startsWith("id: ")) {
                    continue;
                }
                long cursor = Long.parseLong(line.substring("id: ".length()).trim());
                assertEquals(expectedCursor, cursor, "SSE overflow catchUp must drop zero cursors");
                expectedCursor++;
            }
        }
    }
}
