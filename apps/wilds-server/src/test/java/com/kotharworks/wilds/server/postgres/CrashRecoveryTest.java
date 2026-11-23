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
package com.kotharworks.wilds.server.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.auth.dev.DevPersona;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.token.ApiTokenCodec;
import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.headless.HeadlessMain;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.state.ActorRow;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.ServerConfig;
import com.kotharworks.wilds.server.WildsServer;
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.adapters.postgres.PostgresAuthStore;
import com.kotharworks.wilds.server.adapters.postgres.PostgresWorldStore;
import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import com.kotharworks.wilds.replay.WalHeader;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Ticket 08 acceptance (3) and (4): a store failure during
 * {@code appendCommand} leaves RAM strictly behind the WAL and the HTTP layer
 * answers 503, and an abrupt {@code kill -9} mid-tasks recovers to the
 * 100% identical world (clock, hash, actor location, event cursor) with zero
 * SSE cursor rewind and zero offline clock jump.
 */
@Timeout(120)
class CrashRecoveryTest {

    // ------------------------------------------------------------------ (3) split-brain immunity

    @Test
    void storeFaultLeavesRamBehindTheWalUntilCleanRetry() {
        Jdbi jdbi = freshJdbi();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore pg = new PostgresWorldStore(jdbi);
        FaultyWorldStore store = new FaultyWorldStore(pg);
        WorldHost host = new WorldHost(store, new InMemoryEventBus(), 1L, 1L,
                WorldHost.DEFAULT_QUIET_ADVANCE_MS, Long.MAX_VALUE);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "split-brain"), "Sam");
        SeatRecord seat = pg.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);

        host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
        OwnerId owner = host.view().findOwnerBySeatKey(seat.seatId()).orElseThrow().id();
        ActorId actor = host.view().actorsOwnedBy(owner).get(0).id();
        long hashBefore = host.worldHash();
        long walBefore = count(jdbi, "SELECT count(*) FROM wilds_world.command_wal");

        store.appendCommandFault = new IllegalStateException("disk down");
        CommandOutcome faulted = host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(1_000L, 1))));
        assertInstanceOf(CommandOutcome.StorageUnavailable.class, faulted,
                "a refused commit reads back as STORAGE_UNAVAILABLE");
        assertEquals(hashBefore, host.worldHash(),
                "RAM never advances ahead of the WAL on a failed commit");
        assertEquals(walBefore, count(jdbi, "SELECT count(*) FROM wilds_world.command_wal"),
                "zero WAL rows for the failed commit");

        store.appendCommandFault = null;
        CommandOutcome retry = host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(1_000L, 1))));
        assertInstanceOf(CommandOutcome.Ok.class, retry);
        assertEquals(walBefore + 1, count(jdbi, "SELECT count(*) FROM wilds_world.command_wal"),
                "the clean retry commits exactly one row");
        assertNotEquals(hashBefore, host.worldHash(), "the retry advances the world");
        host.close();
    }

    @Test
    void storeFaultAnswersHttp503WithUntouchedWorld() throws Exception {
        Jdbi jdbi = freshJdbi();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore pg = new PostgresWorldStore(jdbi);
        FaultyWorldStore store = new FaultyWorldStore(pg);
        ServerConfig config = new ServerConfig(0, 1L, 1L, 60_000L, AuthMode.DEV_LOCAL,
                "http://127.0.0.1:0", null, null, null, null, null, null, 100L, 200L,
                "127.0.0.1", ServerConfig.StorageMode.MEMORY, null, null, null, Long.MAX_VALUE);
        WildsServer server = new WildsServer(config, Clock.systemUTC(), auth, store,
                new InMemoryEventBus(), new ApiTokenCodec(new SecureRandom()));
        server.start();
        String sam = server.devIssuer().mintSignedJwt(DevPersona.SAM);
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.port();
        try {
            HttpResponse<String> seat = http.send(post(base + "/api/v1/seats",
                    "{\"seatName\":\"Sam Seat\",\"controllerKind\":\"HUMAN\"}", sam),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, seat.statusCode(), seat.body());
            String actor = jsonField(seat.body(), "actorId");
            HttpResponse<String> submitted = http.send(post(base + "/api/v1/commands",
                    "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                            + "\"targetActor\":\"" + actor + "\",\"expectedRevision\":null,"
                            + "\"tasks\":[{\"type\":\"wait\",\"durationMs\":1000,\"batches\":1}]}",
                    sam), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, submitted.statusCode(), submitted.body());

            long hashBefore = server.worldHost().worldHash();
            store.appendCommandFault = new IllegalStateException("disk down");
            HttpResponse<String> faulted = http.send(post(base + "/api/v1/commands",
                    "{\"cmdId\":\"" + UUID.randomUUID() + "\",\"type\":\"enqueue\","
                            + "\"targetActor\":\"" + actor + "\",\"expectedRevision\":null,"
                            + "\"tasks\":[{\"type\":\"wait\",\"durationMs\":1000,\"batches\":1}]}",
                    sam), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, faulted.statusCode(), faulted.body());
            assertTrue(faulted.body().contains("STORAGE_UNAVAILABLE"),
                    "the 503 body names the outcome: " + faulted.body());
            assertEquals(hashBefore, server.worldHost().worldHash(),
                    "RAM never advances ahead of the WAL on a failed commit");
            store.appendCommandFault = null;
        } finally {
            server.close();
        }
    }

    @Test
    void brokenConnectionReadsBackAsStorageUnavailable() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(db.jdbcUrl());
        dataSource.setUsername(db.username());
        dataSource.setPassword(db.password());
        dataSource.setMaximumPoolSize(4);
        dataSource.setPoolName("wilds-crash-test");
        try {
            Jdbi jdbi = Jdbi.create(dataSource);
            new SqlMigrator(jdbi).migrate();
            PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                    new SecureRandom());
            PostgresWorldStore pg = new PostgresWorldStore(jdbi);
            WorldHost host = new WorldHost(pg, new InMemoryEventBus(), 1L, 1L,
                    WorldHost.DEFAULT_QUIET_ADVANCE_MS, Long.MAX_VALUE);
            UserRecord user = auth.upsertUser(
                    new ExternalIdentity("https://id.example:8443", "broken-pipe"), "Sam");
            SeatRecord seat = pg.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);
            host.submit(seat.seatId(), UUID.randomUUID(),
                    new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
            long hashBefore = host.worldHash();
            dataSource.close(); // the broken connection

            long startedNanos = System.nanoTime();
            CommandOutcome outcome = host.submit(seat.seatId(), UUID.randomUUID(),
                    new WorldCommand.EnqueueTasks(
                            host.view().findOwnerBySeatKey(seat.seatId()).orElseThrow().id(),
                            host.view().actorsOwnedBy(
                                    host.view().findOwnerBySeatKey(seat.seatId())
                                            .orElseThrow().id()).get(0).id(),
                            null, List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(
                                    1_000L, 1))));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            CommandOutcome.StorageUnavailable unavailable = assertInstanceOf(
                    CommandOutcome.StorageUnavailable.class, outcome);
            assertFalse(unavailable.message().contains("did not reply within"),
                    "writer must catch the dead pool directly instead of dying into a timeout: "
                            + unavailable.message());
            assertTrue(elapsedMs < 5_000L,
                    "dead-pool reply must be immediate, not the 10s ReplySlot backstop: "
                            + elapsedMs + "ms");
            assertEquals(hashBefore, host.worldHash(),
                    "RAM never advances ahead of the WAL on a dead pool");
            host.close();
        } finally {
            try {
                dataSource.close();
            } catch (RuntimeException alreadyClosed) {
                // first close already tore the pool down
            }
        }
    }

    @Test
    void writerSurvivesEveryStoreCallOutageAndRecoversCleanly() throws Exception {
        Jdbi jdbi = freshJdbi();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore pg = new PostgresWorldStore(jdbi);
        FaultyWorldStore store = new FaultyWorldStore(pg);
        WorldHost host = new WorldHost(store, new InMemoryEventBus(), 1L, 20L,
                WorldHost.DEFAULT_QUIET_ADVANCE_MS, Long.MAX_VALUE);
        int worldId = host.worldId();
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "resilient-writer"), "Sam");
        SeatRecord seat = pg.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);
        host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
        OwnerId owner = host.view().findOwnerBySeatKey(seat.seatId()).orElseThrow().id();
        ActorId actor = host.view().actorsOwnedBy(owner).get(0).id();

        // (a) existsByCmdId fault: immediate StorageUnavailable, writer stays alive.
        store.existsByCmdIdFault = new IllegalStateException("pool down on cmdId lookup");
        CommandOutcome existsFaulted = host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(1_000L, 1))));
        CommandOutcome.StorageUnavailable existsUnavailable = assertInstanceOf(
                CommandOutcome.StorageUnavailable.class, existsFaulted);
        assertFalse(existsUnavailable.message().contains("did not reply within"),
                existsUnavailable.message());
        store.existsByCmdIdFault = null;

        // Enqueue a short 1_000ms wait once the store is back.
        assertInstanceOf(CommandOutcome.Ok.class, host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(1_000L, 1)))));

        // (b) appendClockAdvance fault: ticks stay paused at the last committed ms.
        store.appendClockAdvanceFault = new IllegalStateException("pool down on clock advance");
        long pausedAt = host.nowMs();
        Thread.sleep(80);
        host.tick();
        Thread.sleep(50);
        assertEquals(pausedAt, host.nowMs(),
                "PAUSE_WHILE_DOWN: failed clock advance leaves logical time untouched");
        store.appendClockAdvanceFault = null;

        // (c) appendEvents fault: WAL-committed tick advances RAM without killing the
        // writer, defers world_events persistence, and withholds checkpoints until flushed.
        long eventsBefore = count(jdbi, "SELECT count(*) FROM wilds_world.world_events"
                + " WHERE world_id = " + worldId);
        store.appendEventsFault = new IllegalStateException("pool blip on event insert");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            host.tick();
            Thread.sleep(50);
            ActorRow snap = host.view().actor(actor).orElseThrow();
            if (snap.activeTask() == null && snap.pendingQueue().isEmpty()) {
                break;
            }
        }
        assertTrue(host.view().actor(actor).orElseThrow().activeTask() == null,
                "tick advanced the world once CLOCK_ADVANCE committed");
        assertEquals(eventsBefore, count(jdbi, "SELECT count(*) FROM wilds_world.world_events"
                        + " WHERE world_id = " + worldId),
                "world_events has not received the deferred batch while appendEvents is faulted");
        host.writeCheckpointNow();
        assertEquals(0L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                        + " WHERE world_id = " + worldId),
                "checkpoint must not advance past WAL entries whose events are still unpersisted");

        // Store recovers: checkpoint flushes deferred events first, then writes snapshot,
        // and subsequent commands still succeed on the original writer thread.
        store.appendEventsFault = null;
        host.writeCheckpointNow();
        assertTrue(count(jdbi, "SELECT count(*) FROM wilds_world.world_events"
                        + " WHERE world_id = " + worldId) > eventsBefore,
                "deferred events flushed idempotently on recovery");
        assertEquals(1L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                        + " WHERE world_id = " + worldId),
                "checkpoint succeeds once deferred events are persisted");
        assertInstanceOf(CommandOutcome.Ok.class, host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(1_000L, 1)))),
                "writer thread remained alive across every outage mode");
        host.close();
    }

    // ------------------------------------------------------------------ (4) kill -9 recovery

    @Test
    void kill9RecoversIdenticallyWithZeroCursorRewindAndZeroOfflineJump() throws Exception {
        Jdbi jdbi = freshJdbi();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore pg = new PostgresWorldStore(jdbi);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "kill-nine"), "Sam");
        SeatRecord seat = pg.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);

        WorldHost host1 = new WorldHost(pg, new InMemoryEventBus(), 1L, 20L, 200L,
                Long.MAX_VALUE);
        int worldId = host1.worldId();
        host1.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
        host1.writeCheckpointNow(); // checkpoint K
        assertEquals(1L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                + " WHERE world_id = " + worldId));

        OwnerId owner = host1.view().findOwnerBySeatKey(seat.seatId()).orElseThrow().id();
        ActorId actor = host1.view().actorsOwnedBy(owner).get(0).id();
        PlaceId destination = host1.view().place(WildsEngine.HOME_PLACE_ID).orElseThrow()
                .edges().get(0).to();
        host1.submit(seat.seatId(), UUID.randomUUID(), new WorldCommand.EnqueueTasks(owner,
                actor, null, List.of(new com.kotharworks.wilds.state.TaskSpec.Travel(destination),
                        new com.kotharworks.wilds.state.TaskSpec.Wait(2_000L, 1))));

        // Drain (host-side ticks; no pump) until the actor is idle.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        boolean idle = false;
        while (System.nanoTime() < deadline) {
            host1.tick();
            Thread.sleep(50);
            ActorRow snapshot = host1.view().actor(actor).orElseThrow();
            if (snapshot.activeTask() == null && snapshot.pendingQueue().isEmpty()) {
                idle = true;
                break;
            }
        }
        assertTrue(idle, "travel+wait completed within the drain deadline at timeScale 20");

        long nowBefore = host1.nowMs();
        long hashBefore = host1.worldHash();
        PlaceId placeBefore = host1.view().actor(actor).orElseThrow().placeId();
        List<StoredWorldEvent> rows = pg.readEventsSince(worldId, seat.seatId(), 0, 10_000);
        long maxCursorBefore = rows.get(rows.size() - 1).cursor();
        long eventCountBefore = count(jdbi, "SELECT count(*) FROM wilds_world.world_events"
                + " WHERE world_id = " + worldId);
        assertEquals(1L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                + " WHERE world_id = " + worldId),
                "kill -9 window: still exactly the one checkpoint");

        // Abrupt kill: stop writer without flushing, anchoring, or checkpointing.
        host1.closeWithoutCheckpoint();

        WorldHost host2 = new WorldHost(pg, new InMemoryEventBus(), 1L, 20L, 200L,
                Long.MAX_VALUE);
        assertEquals(nowBefore, host2.nowMs(),
                "PAUSE_WHILE_DOWN: the recovered clock resumes exactly where it stopped");
        assertEquals(hashBefore, host2.worldHash(), "recovered world hash identical");
        assertEquals(placeBefore, host2.view().actor(actor).orElseThrow().placeId(),
                "recovered actor location identical");
        List<StoredWorldEvent> recovered = pg.readEventsSince(worldId, seat.seatId(), 0,
                10_000);
        assertEquals(maxCursorBefore, recovered.get(recovered.size() - 1).cursor(),
                "zero SSE cursor rewind across the crash");
        assertEquals(eventCountBefore, count(jdbi, "SELECT count(*) FROM wilds_world.world_events"
                        + " WHERE world_id = " + worldId),
                "backfill re-runs add nothing (ON CONFLICT DO NOTHING)");
        assertEquals(1L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                + " WHERE world_id = " + worldId),
                "no new checkpoint during the crash window");

        host2.close();
        assertEquals(2L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                + " WHERE world_id = " + worldId),
                "the graceful close writes its own checkpoint");

        StringWriter out = new StringWriter();
        pg.exportWalJsonl(worldId, out);
        List<String> lines = out.toString().lines().toList();
        assertEquals(new WalHeader(1L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION).encode(), lines.get(0));
        long expectedSeq = 1L;
        for (int i = 1; i < lines.size(); i++) {
            WalRecord record = CommandCodec.decodeWal(lines.get(i));
            long seq = switch (record) {
                case WalRecord.ClockAdvance advance -> advance.walSeq();
                case WalRecord.CommandEntry entry -> entry.walSeq();
            };
            assertEquals(expectedSeq++, seq, "exported WAL stays dense for headless replay");
        }
    }

    // ------------------------------------------------------------------ (4b) kill -9 before the first checkpoint

    /**
     * Review regression: a crash before the FIRST checkpoint must recover from
     * the WAL tail alone (up_to_wal_seq = 0) — acknowledged commands survive,
     * receipts answer identically, and a checkpoint written before any new
     * append pins the WAL watermark (never 0, which would poison the next boot
     * with a full-WAL re-apply).
     */
    @Test
    void kill9BeforeFirstCheckpointRecoversAndCheckpointsPinTheWalWatermark() throws Exception {
        Jdbi jdbi = freshJdbi();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore pg = new PostgresWorldStore(jdbi);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "pre-checkpoint-kill"), "Sam");
        SeatRecord seat = pg.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);

        WorldHost host1 = new WorldHost(pg, new InMemoryEventBus(), 1L, 20L, 200L,
                Long.MAX_VALUE);
        int worldId = host1.worldId();
        UUID charter = UUID.randomUUID();
        host1.submit(seat.seatId(), charter,
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
        OwnerId owner = host1.view().findOwnerBySeatKey(seat.seatId()).orElseThrow().id();
        ActorId actor = host1.view().actorsOwnedBy(owner).get(0).id();
        UUID enqueue = UUID.randomUUID();
        host1.submit(seat.seatId(), enqueue,
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(2_000L, 1))));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        boolean idle = false;
        while (System.nanoTime() < deadline) {
            host1.tick();
            Thread.sleep(50);
            ActorRow snapshot = host1.view().actor(actor).orElseThrow();
            if (snapshot.activeTask() == null && snapshot.pendingQueue().isEmpty()) {
                idle = true;
                break;
            }
        }
        assertTrue(idle, "wait completed within the drain deadline at timeScale 20");
        long nowBefore = host1.nowMs();
        long hashBefore = host1.worldHash();
        assertEquals(0L, count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                        + " WHERE world_id = " + worldId),
                "kill -9 lands before the first checkpoint ever exists");
        host1.closeWithoutCheckpoint();

        // Recovery from the WAL tail alone: fresh seeded engine + full replay.
        WorldHost host2 = new WorldHost(pg, new InMemoryEventBus(), 1L, 20L, 200L,
                Long.MAX_VALUE);
        assertEquals(nowBefore, host2.nowMs(),
                "clock resumes exactly where the crashed host stopped");
        assertEquals(hashBefore, host2.worldHash(),
                "WAL-only replay reproduces the identical world hash");

        // The replayed receipt cache answers the same cmdId without re-executing.
        CommandOutcome duplicate = host2.submit(seat.seatId(), enqueue,
                new WorldCommand.EnqueueTasks(owner, actor, null,
                        List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(2_000L, 1))));
        assertInstanceOf(CommandOutcome.Ok.class, duplicate);
        assertEquals(enqueue, ((CommandOutcome.Ok) duplicate).receipt().cmdId());
        assertTrue(((CommandOutcome.Ok) duplicate).receipt().walSeq() > 0,
                "receipt rebuilt from the WAL row carries its wal_seq");
        assertEquals(hashBefore, host2.worldHash(), "the duplicate never re-executes");

        // A checkpoint written before any new append must pin the WAL watermark.
        host2.writeCheckpointNow();
        List<WalRecord> wal = pg.loadWalAfter(worldId, 0L);
        long watermark = switch (wal.get(wal.size() - 1)) {
            case WalRecord.ClockAdvance advance -> advance.walSeq();
            case WalRecord.CommandEntry entry -> entry.walSeq();
        };
        assertEquals(watermark, pg.loadLatestCheckpoint(worldId).orElseThrow().upToWalSeq(),
                "checkpoint carries the highest replayed wal_seq, never 0");

        host2.close(); // graceful close advances to currentLogicalMs and checkpoints
        long hashAfterHost2Close = host2.worldHash();

        // Boot 3: a short checkpoint interval must not poison the next boot even
        // when the periodic write fires on an idle world before any new append.
        WorldHost host3 = new WorldHost(pg, new InMemoryEventBus(), 1L, 20L, 200L, 50L);
        assertEquals(hashAfterHost2Close, host3.worldHash(),
                "boot from the watermark-pinned checkpoint never re-applies the WAL");
        // the expected watermark now includes close()'s final anchor row
        List<WalRecord> afterClose = pg.loadWalAfter(worldId, 0L);
        long watermarkAfterClose = switch (afterClose.get(afterClose.size() - 1)) {
            case WalRecord.ClockAdvance advance -> advance.walSeq();
            case WalRecord.CommandEntry entry -> entry.walSeq();
        };
        long deadline3 = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                + " WHERE world_id = " + worldId) < 2 && System.nanoTime() < deadline3) {
            host3.tick(); // idle world: the periodic path fires on the early return
            Thread.sleep(100);
        }
        assertTrue(count(jdbi, "SELECT count(*) FROM wilds_world.world_checkpoints"
                        + " WHERE world_id = " + worldId) >= 2,
                "the idle-world periodic checkpoint fired");
        assertEquals(watermarkAfterClose,
                pg.loadLatestCheckpoint(worldId).orElseThrow().upToWalSeq(),
                "the periodic idle checkpoint keeps pinning the WAL watermark");
        host3.close();
    }

    @Test
    void preCheckpointCmdIdRetryGracefulCloseAdvanceAndClockRegressionGuard(
            @TempDir Path tempDir) throws Exception {
        Jdbi jdbi = freshJdbi();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore pg = new PostgresWorldStore(jdbi);
        UserRecord samUser = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "sam-precp"), "Sam");
        UserRecord aliceUser = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "alice-precp"), "Alice");
        SeatRecord samSeat = pg.createSeat(samUser.userId(), "Sam Seat", ControllerKind.HUMAN);
        SeatRecord aliceSeat = pg.createSeat(aliceUser.userId(), "Alice Seat",
                ControllerKind.HUMAN);

        long[] wallNanos = new long[] {1_000_000_000L};
        WorldHost host1 = new WorldHost(pg, new InMemoryEventBus(), () -> wallNanos[0],
                7L, 1L, 60_000L, Long.MAX_VALUE);
        int worldId = host1.worldId();
        host1.submit(samSeat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(samSeat.seatId(), "Sam", "Sam Actor"));
        host1.submit(aliceSeat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(aliceSeat.seatId(), "Alice", "Alice Actor"));
        OwnerId samOwner = host1.view().findOwnerBySeatKey(samSeat.seatId()).orElseThrow().id();
        ActorId samActor = host1.view().actorsOwnedBy(samOwner).getFirst().id();
        OwnerId aliceOwner = host1.view().findOwnerBySeatKey(aliceSeat.seatId()).orElseThrow().id();
        ActorId aliceActor = host1.view().actorsOwnedBy(aliceOwner).getFirst().id();

        // Commit command X at admittedMs = 250ms, then write checkpoint subsuming X.
        wallNanos[0] += TimeUnit.MILLISECONDS.toNanos(250);
        UUID preCheckpointCmdId = UUID.randomUUID();
        CommandOutcome.Ok original = assertInstanceOf(CommandOutcome.Ok.class,
                host1.submit(samSeat.seatId(), preCheckpointCmdId,
                        new WorldCommand.EnqueueTasks(samOwner, samActor, null,
                                List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(
                                        10_000L, 1)))));
        assertEquals(250L, original.receipt().admittedMs());
        host1.writeCheckpointNow();

        // Advance +3_750ms of quiet online wall time (total t = 4_000ms, < 60_000ms quiet
        // threshold, with zero ticks): graceful close must anchor currentLogicalMs = 4_000ms!
        wallNanos[0] += TimeUnit.MILLISECONDS.toNanos(3_750);
        host1.close();
        assertEquals(4_000L, host1.nowMs(),
                "graceful close must advance to currentLogicalMs (4_000ms)");

        // Simulate 30 seconds of offline maintenance downtime: PAUSE_WHILE_DOWN must ignore it!
        wallNanos[0] += TimeUnit.SECONDS.toNanos(30);
        WorldHost host2 = new WorldHost(pg, new InMemoryEventBus(), () -> wallNanos[0],
                7L, 1L, 60_000L, Long.MAX_VALUE);
        try {
            assertEquals(4_000L, host2.nowMs(),
                    "PAUSE_WHILE_DOWN: 4s online preserved, 30s offline ignored");
            long walCountAfterBoot = count(jdbi,
                    "SELECT count(*) FROM wilds_world.command_wal WHERE world_id = " + worldId);

            // Owning seat retrying pre-checkpoint cmdId (not in receiptCache!) hits
            // PostgresWorldStore.findCommandByCmdId and gets exact walSeq & admittedMs.
            CommandOutcome.Ok retried = assertInstanceOf(CommandOutcome.Ok.class,
                    host2.submit(samSeat.seatId(), preCheckpointCmdId,
                            new WorldCommand.EnqueueTasks(samOwner, samActor, null,
                                    List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(
                                            10_000L, 1)))));
            assertEquals(original.receipt().walSeq(), retried.receipt().walSeq());
            assertEquals(original.receipt().admittedMs(), retried.receipt().admittedMs());

            // Foreign seat probing the same pre-checkpoint cmdId gets FORBIDDEN.
            CommandOutcome.Rejected foreignProbe = assertInstanceOf(CommandOutcome.Rejected.class,
                    host2.submit(aliceSeat.seatId(), preCheckpointCmdId,
                            new WorldCommand.EnqueueTasks(aliceOwner, aliceActor, null,
                                    List.of(new com.kotharworks.wilds.state.TaskSpec.Wait(
                                            1_000L, 1)))));
            assertEquals("FORBIDDEN", foreignProbe.errorCode());
            assertEquals(walCountAfterBoot, count(jdbi,
                    "SELECT count(*) FROM wilds_world.command_wal WHERE world_id = " + worldId),
                    "pre-checkpoint retries/probes must write zero new WAL rows");

            // Exported WAL replays in wilds-headless to the exact WorldHash.
            StringWriter walOut = new StringWriter();
            pg.exportWalJsonl(worldId, walOut);
            Path walPath = tempDir.resolve("graceful-close.wal.jsonl");
            Files.writeString(walPath, walOut.toString());
            StringWriter hOut = new StringWriter();
            StringWriter hErr = new StringWriter();
            assertEquals(0, HeadlessMain.execute(
                    new String[] {"replay", "--seed", "7", "--wal", walPath.toString(), "--hash"},
                    new PrintWriter(hOut), new PrintWriter(hErr)), hErr.toString());
            assertTrue(hOut.toString().contains(String.format("worldHash=0x%016x",
                    host2.worldHash())), hOut.toString());
        } finally {
            host2.close();
        }

        // Corrupt backward admitted_ms in command_wal must fail fast on boot recovery.
        Jdbi regressedDb = freshJdbi();
        PostgresWorldStore regressedStore = new PostgresWorldStore(regressedDb);
        int regressedWorldId = regressedStore.ensureActiveWorld(3L,
                RuleRegistry.WILDS_RULES_VERSION, WildsEngine.API_VERSION,
                WorldHost.WILDS_PROTOCOL_VERSION).worldId();
        regressedStore.appendClockAdvance(regressedWorldId, 5_000L, null);
        regressedStore.appendClockAdvance(regressedWorldId, 4_999L, null);
        assertThrows(IllegalStateException.class,
                () -> new WorldHost(regressedStore, new InMemoryEventBus(), 3L, 1L,
                        60_000L, Long.MAX_VALUE),
                "boot WAL replay must fail fast on clock regression");
    }

    // ------------------------------------------------------------------ rig

    private static Jdbi freshJdbi() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        return jdbi;
    }

    private static long count(Jdbi jdbi, String sql) {
        try (Handle handle = jdbi.open()) {
            return handle.select(sql).mapTo(long.class).one();
        }
    }

    private static HttpRequest post(String url, String body, String bearer) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return builder.build();
    }

    /** Crude top-level string field extraction for the charter body. */
    private static String jsonField(String body, String field) {
        int key = body.indexOf("\"" + field + "\":\"");
        int start = key + field.length() + 4; // skip past "\"field\":\""
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /**
     * Delegating port wrapper with armable store faults — the DB-side
     * constraint/connection failure stand-in across every writer-thread call.
     */
    static final class FaultyWorldStore implements WorldStorePort {

        private final WorldStorePort delegate;
        volatile RuntimeException existsByCmdIdFault;
        volatile RuntimeException appendClockAdvanceFault;
        volatile RuntimeException appendCommandFault;
        volatile RuntimeException appendEventsFault;

        FaultyWorldStore(WorldStorePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public SeatRecord createSeat(UserId owner, String seatName, ControllerKind kind) {
            return delegate.createSeat(owner, seatName, kind);
        }

        @Override
        public List<SeatRecord> findSeatsByOwner(UserId owner) {
            return delegate.findSeatsByOwner(owner);
        }

        @Override
        public List<SeatRecord> findAllSeats() {
            return delegate.findAllSeats();
        }

        @Override
        public java.util.Optional<SeatRecord> findSeatById(long seatId) {
            return delegate.findSeatById(seatId);
        }

        @Override
        public WorldRecord ensureActiveWorld(long worldSeed, int rulesVersion,
                int engineVersion, int protocolVersion) {
            return delegate.ensureActiveWorld(worldSeed, rulesVersion, engineVersion,
                    protocolVersion);
        }

        @Override
        public java.util.Optional<WalRecord.CommandEntry> findCommandByCmdId(int worldId,
                UUID cmdId) {
            RuntimeException fault = existsByCmdIdFault;
            if (fault != null) {
                throw fault;
            }
            return delegate.findCommandByCmdId(worldId, cmdId);
        }

        @Override
        public boolean existsByCmdId(int worldId, UUID cmdId) {
            RuntimeException fault = existsByCmdIdFault;
            if (fault != null) {
                throw fault;
            }
            return delegate.existsByCmdId(worldId, cmdId);
        }

        @Override
        public long appendClockAdvance(int worldId, long admittedMs, Long worldHash) {
            RuntimeException fault = appendClockAdvanceFault;
            if (fault != null) {
                throw fault;
            }
            return delegate.appendClockAdvance(worldId, admittedMs, worldHash);
        }

        @Override
        public long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId,
                WorldCommand cmd, Long worldHash) {
            RuntimeException fault = appendCommandFault;
            if (fault != null) {
                throw fault;
            }
            return delegate.appendCommand(worldId, admittedMs, seatId, cmdId, cmd, worldHash);
        }

        @Override
        public void appendEvents(int worldId, List<StoredWorldEvent> events) {
            RuntimeException fault = appendEventsFault;
            if (fault != null) {
                throw fault;
            }
            delegate.appendEvents(worldId, events);
        }

        @Override
        public List<StoredWorldEvent> readEventsSince(int worldId, Long seatIdOrNullForPublic,
                long sinceCursor, int limit) {
            return delegate.readEventsSince(worldId, seatIdOrNullForPublic, sinceCursor, limit);
        }

        @Override
        public void saveCheckpoint(int worldId, long upToWalSeq, long logicalMs, long worldHash,
                String stateJson) {
            delegate.saveCheckpoint(worldId, upToWalSeq, logicalMs, worldHash, stateJson);
        }

        @Override
        public java.util.Optional<StoredCheckpoint> loadLatestCheckpoint(int worldId) {
            return delegate.loadLatestCheckpoint(worldId);
        }

        @Override
        public List<WalRecord> loadWalAfter(int worldId, long afterWalSeq) {
            return delegate.loadWalAfter(worldId, afterWalSeq);
        }

        @Override
        public void exportWalJsonl(int worldId, java.io.Writer out) throws IOException {
            delegate.exportWalJsonl(worldId, out);
        }
    }

}
