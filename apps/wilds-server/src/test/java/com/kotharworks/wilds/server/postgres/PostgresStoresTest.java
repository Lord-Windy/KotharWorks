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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.auth.dev.DevPersona;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.adapters.postgres.PostgresAuthStore;
import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;
import com.kotharworks.wilds.server.adapters.postgres.PostgresWorldStore;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.replay.WalHeader;

/**
 * Ticket 08 acceptance (2) plus the durable store contracts: concurrent JIT
 * identity provisioning, the version-pinned world, seat uniqueness, idempotent
 * event backfill, checkpoint retention, and WAL export byte-parity with the
 * {@code wilds-headless} header.
 */
@Timeout(120)
class PostgresStoresTest {

    // ------------------------------------------------------------------ (2) concurrent JIT

    @Test
    void twentyConcurrentLoginsProvisionExactlyOneUser() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        ExternalIdentity identity = new ExternalIdentity("https://id.example:8443",
                "jit-concurrent-subject");

        int logins = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<UserRecord>> logins20 = new ArrayList<>(logins);
        for (int i = 0; i < logins; i++) {
            logins20.add(() -> {
                startGate.await();
                return auth.upsertUser(identity, "Sam");
            });
        }
        List<Future<UserRecord>> futures;
        startGate.countDown();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = executor.invokeAll(logins20);
        }
        Set<UUID> minted = new java.util.HashSet<>();
        for (Future<UserRecord> future : futures) {
            minted.add(future.get().userId().value());
        }
        assertEquals(1, minted.size(), "every concurrent login lands on the same user id");

        try (var handle = jdbi.open()) {
            assertEquals(1L, count(handle, "SELECT count(*) FROM auth.users"),
                    "exactly one users row, zero orphans");
            assertEquals(1L, count(handle, "SELECT count(*) FROM auth.identity_links"),
                    "exactly one identity link");
        }

        UserRecord second = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "second-subject"), "Other");
        assertNotEquals(minted.iterator().next(), second.userId().value(),
                "a distinct identity mints a distinct user");
        try (var handle = jdbi.open()) {
            assertEquals(2L, count(handle, "SELECT count(*) FROM auth.users"));
        }
    }

    // ------------------------------------------------------------------ world pinning

    @Test
    void ensureActiveWorldIsVersionPinned() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);

        WorldRecord world = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION);
        assertTrue(world.worldId() >= 1);
        WorldRecord again = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION);
        assertEquals(world, again, "the same triple re-reads the same world row");

        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION + 1,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION),
                "a different version triple refuses to boot");
        assertTrue(mismatch.getMessage().contains("version triple"),
                "message mirrors the in-memory store: " + mismatch.getMessage());
    }

    // ------------------------------------------------------------------ seat metadata

    @Test
    void duplicateSeatNamesAreRejectedAndOwnersMustExist() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "seat-owner"), "Sam");

        SeatRecord seat = store.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);
        assertTrue(seat.seatId() >= 1);
        IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(user.userId(), "  Sam Seat  ", ControllerKind.HUMAN),
                "trimming reveals the duplicate");
        assertTrue(duplicate.getMessage().contains("DUPLICATE_SEAT"),
                "message mirrors the in-memory store: " + duplicate.getMessage());

        IllegalArgumentException ghost = assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(new UserId(UUID.randomUUID()), "Ghost Seat",
                        ControllerKind.HUMAN),
                "a seat without an owner row violates the FK");
        assertTrue(ghost.getMessage().contains("unknown owner"),
                "FK violation reads as an unknown owner: " + ghost.getMessage());

        List<SeatRecord> seats = store.findSeatsByOwner(user.userId());
        assertEquals(1, seats.size());
        assertEquals(seat, store.findSeatById(seat.seatId()).orElseThrow());
    }

    // ------------------------------------------------------------------ event log

    @Test
    void eventBackfillIsIdempotentAndPublicFeedsStayBlind() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "event-owner"), "Sam");
        SeatRecord seat = store.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);

        WorldHost host = new WorldHost(store, new InMemoryEventBus(), 1L, 1L,
                WorldHost.DEFAULT_QUIET_ADVANCE_MS, Long.MAX_VALUE);
        UUID cmdId = UUID.randomUUID();
        CommandOutcome outcome = host.submit(seat.seatId(), cmdId,
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
        assertInstanceOf(CommandOutcome.Ok.class, outcome);

        int worldId = host.worldId();
        List<StoredWorldEvent> privateRows = store.readEventsSince(worldId, seat.seatId(),
                0, 100);
        assertFalse(privateRows.isEmpty(), "the charter emits at least owner_registered");
        assertTrue(privateRows.stream().allMatch(row -> row.seatId() != null),
                "full rows carry their seat");
        assertTrue(privateRows.stream().allMatch(row -> row.privateSummary() != null),
                "full rows carry the private summary");
        assertTrue(privateRows.stream().allMatch(row -> row.event() != null),
                "full rows carry the decoded payload");

        List<StoredWorldEvent> publicRows = store.readEventsSince(worldId, null, 0, 100);
        assertFalse(publicRows.isEmpty(), "owner_registered is world-wire safe");
        assertTrue(publicRows.stream().allMatch(row -> row.privateSummary() == null
                        && row.event() == null),
                "public projection structurally cannot carry private detail");

        // Idempotent backfill: re-appending the same rows adds nothing.
        store.appendEvents(worldId, privateRows);
        List<StoredWorldEvent> afterBackfill = store.readEventsSince(worldId, seat.seatId(),
                0, 100);
        assertEquals(privateRows.size(), afterBackfill.size(),
                "ON CONFLICT (world_id, event_cursor) DO NOTHING keeps the log append-once");
        host.close();
    }

    // ------------------------------------------------------------------ checkpoints

    @Test
    void checkpointRoundTripRetainsLatestTen() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldId = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION).worldId();

        store.saveCheckpoint(worldId, 5L, 1_000L, 42L, "{\"tick\":1}");
        for (int i = 2; i <= 12; i++) {
            store.saveCheckpoint(worldId, 5L + i, 1_000L * i, 42L + i, "{\"tick\":" + i + "}");
        }
        try (var handle = jdbi.open()) {
            assertEquals(10L, count(handle, "SELECT count(*) FROM wilds_world.world_checkpoints"
                    + " WHERE world_id = " + worldId),
                    "retention prunes everything older than the latest ten");
        }
        StoredCheckpoint latest = store.loadLatestCheckpoint(worldId).orElseThrow();
        assertEquals(17L, latest.upToWalSeq());
        assertEquals(12_000L, latest.logicalMs());
        assertEquals(54L, latest.worldHash());
        // jsonb normalizes spacing: the stored text gains a space after the colon
        assertEquals("{\"tick\": 12}", latest.stateJson());
    }

    // ------------------------------------------------------------------ WAL export

    @Test
    void exportWalJsonlIsByteCompatibleWithHeadlessReplay() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "wal-export-owner"), "Sam");
        SeatRecord seat = store.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);

        WorldHost host = new WorldHost(store, new InMemoryEventBus(), 9L, 1L,
                WorldHost.DEFAULT_QUIET_ADVANCE_MS, Long.MAX_VALUE);
        int worldId = host.worldId();
        host.submit(seat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"));
        long anchor = store.appendClockAdvance(worldId, host.nowMs(), null);

        java.io.StringWriter out = new java.io.StringWriter();
        store.exportWalJsonl(worldId, out);
        List<String> lines = out.toString().lines().toList();

        assertEquals(new WalHeader(9L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION).encode(),
                lines.get(0), "line 1 is the canonical WAL_HEADER");
        long expectedSeq = 1L;
        for (int i = 1; i < lines.size(); i++) {
            WalRecord record = CommandCodec.decodeWal(lines.get(i));
            long seq = switch (record) {
                case WalRecord.ClockAdvance advance -> advance.walSeq();
                case WalRecord.CommandEntry entry -> entry.walSeq();
            };
            assertEquals(expectedSeq, seq, "wal_seq must be dense ascending for replay");
            expectedSeq++;
        }
        assertEquals(anchor + 1, expectedSeq, "every WAL row was exported exactly once");
        host.close();
    }

    // ------------------------------------------------------------------ helpers

    private static long count(org.jdbi.v3.core.Handle handle, String sql) {
        return handle.select(sql).mapTo(long.class).one();
    }

}
