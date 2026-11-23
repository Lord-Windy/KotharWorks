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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.ScopedTokenRecord;
import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.ErrorCode;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.headless.HeadlessMain;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.adapters.postgres.PostgresAuthStore;
import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;
import com.kotharworks.wilds.server.adapters.postgres.PostgresWorldStore;
import com.kotharworks.wilds.server.adapters.postgres.WorldEventPayloadCodec;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.UserRecord;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.state.ActiveTask;
import com.kotharworks.wilds.state.TaskSpec;

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
    void ensureActiveWorldIsVersionPinnedAndConcurrentSafe() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);

        // 10 concurrent first-boot callers must serialize cleanly without 23505 races.
        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<WorldRecord>> boots = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            boots.add(() -> {
                gate.await();
                return store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION);
            });
        }
        List<Future<WorldRecord>> futures;
        gate.countDown();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = executor.invokeAll(boots);
        }
        WorldRecord world = futures.getFirst().get();
        assertTrue(world.worldId() >= 1);
        for (Future<WorldRecord> future : futures) {
            assertEquals(world, future.get(), "every concurrent boot resolves the same world row");
        }

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
    void duplicateSeatNamesSupplementaryLengthsAndMissingOwnersAreHandledCleanly() {
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

        // 2 supplementary-plane emojis = 4 UTF-16 code units, 2 Unicode code points (< 3):
        // must reject with IllegalArgumentException (not an uncaught 23514 SQL error).
        assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(user.userId(), "\uD83C\uDF32\uD83C\uDF32",
                        ControllerKind.HUMAN));
        SeatRecord emojiSeat = store.createSeat(user.userId(),
                "\uD83C\uDF32\uD83C\uDF32\uD83C\uDF32", ControllerKind.SCRIPT_BOT);
        assertEquals(3, emojiSeat.seatName().codePointCount(0, emojiSeat.seatName().length()));

        IllegalArgumentException ghost = assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(new UserId(UUID.randomUUID()), "Ghost Seat",
                        ControllerKind.HUMAN),
                "a seat without an owner row violates the FK");
        assertTrue(ghost.getMessage().contains("unknown owner"),
                "FK violation reads as an unknown owner: " + ghost.getMessage());

        List<SeatRecord> seats = store.findSeatsByOwner(user.userId());
        assertEquals(2, seats.size());
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

    // ------------------------------------------------------------------ WAL export & multi-world

    @Test
    void exportWalJsonlStreamsDenseWalAcrossRollbacksAndMultipleWorlds(
            @TempDir Path tempDir) throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "wal-export-owner"), "Sam");
        SeatRecord seat = store.createSeat(user.userId(), "Sam Seat", ControllerKind.HUMAN);

        int world1 = store.ensureActiveWorld(9L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION).worldId();
        UUID registerCmdId = UUID.randomUUID();
        assertEquals(1L, store.appendCommand(world1, 0L, seat.seatId(), registerCmdId,
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"), null));

        // Rolled-back duplicate cmdId insert must NOT burn a wal_seq gap!
        assertThrows(IllegalStateException.class, () -> store.appendCommand(world1, 10L,
                seat.seatId(), registerCmdId,
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"), null));
        long anchor = store.appendClockAdvance(world1, 500L, null);
        assertEquals(2L, anchor, "wal_seq stays dense (no gap after rolled-back 23505 insert)");

        StringWriter out1 = new StringWriter();
        store.exportWalJsonl(world1, out1);
        List<String> lines1 = out1.toString().lines().toList();
        assertEquals(new WalHeader(9L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION).encode(),
                lines1.get(0), "line 1 is the canonical WAL_HEADER");
        assertEquals(3, lines1.size());
        Path wal1Path = tempDir.resolve("world1.jsonl");
        Files.writeString(wal1Path, out1.toString());
        StringWriter hOut1 = new StringWriter();
        StringWriter hErr1 = new StringWriter();
        assertEquals(0, HeadlessMain.execute(
                new String[] {"replay", "--seed", "9", "--wal", wal1Path.toString(), "--hash"},
                new PrintWriter(hOut1), new PrintWriter(hErr1)), hErr1.toString());

        // Archive world 1 and create world 2: world 2 must start cleanly at wal_seq = 1
        // and replay cleanly in wilds-headless!
        try (var handle = jdbi.open()) {
            handle.execute("UPDATE wilds_meta.worlds SET status = 'ARCHIVED', ended_at = now()"
                    + " WHERE world_id = ?", world1);
        }
        int world2 = store.ensureActiveWorld(11L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION).worldId();
        assertNotEquals(world1, world2);
        assertEquals(1L, store.appendCommand(world2, 0L, seat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"), null),
                "second world starts its own dense wal_seq at 1");
        assertEquals(2L, store.appendClockAdvance(world2, 1_000L, null));

        StringWriter out2 = new StringWriter();
        store.exportWalJsonl(world2, out2);
        Path wal2Path = tempDir.resolve("world2.jsonl");
        Files.writeString(wal2Path, out2.toString());
        StringWriter hOut2 = new StringWriter();
        StringWriter hErr2 = new StringWriter();
        assertEquals(0, HeadlessMain.execute(
                new String[] {"replay", "--seed", "11", "--wal", wal2Path.toString(), "--hash"},
                new PrintWriter(hOut2), new PrintWriter(hErr2)), hErr2.toString());
    }

    // ------------------------------------------------------------------ AuthStore & EventCodec

    @Test
    void postgresAuthStoreSessionsTokensSuspensionAndLastSeenLifecycle() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();

        AtomicReference<Instant> nowRef = new AtomicReference<>(
                Instant.parse("2026-06-01T12:00:00Z"));
        Clock mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return nowRef.get();
            }
        };
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, mutableClock, new SecureRandom());

        // (a) Delimiter-ambiguous (issuer, subject) pairs mint distinct users.
        UserRecord u1 = auth.upsertUser(
                new ExternalIdentity("https://id.example\na", "b"), "One");
        UserRecord u2 = auth.upsertUser(
                new ExternalIdentity("https://id.example", "a\nb"), "Two");
        assertNotEquals(u1.userId(), u2.userId(),
                "delimiter-shifted (issuer, subject) pairs must never collide");

        // (b) Same-name repeat login advances last_seen_at in auth.users.
        ExternalIdentity samId = new ExternalIdentity("https://id.example", "sam");
        UserRecord sam = auth.upsertUser(samId, "Sam");
        Instant initialSeen = readLastSeen(jdbi, sam.userId());
        assertEquals(Instant.parse("2026-06-01T12:00:00Z"), initialSeen);
        nowRef.set(Instant.parse("2026-06-01T12:05:00Z"));
        auth.upsertUser(samId, "Sam"); // unchanged displayName
        assertEquals(Instant.parse("2026-06-01T12:05:00Z"), readLastSeen(jdbi, sam.userId()),
                "repeat login with unchanged display_name must still refresh last_seen_at");

        // (c) Web sessions: multi-role text[] across >= 8 lookups (past pgjdbc's 5-run
        // server-prepare threshold), URL-safe CSRF token, expiry, and revocation.
        Set<GlobalRole> allRoles = Set.of(GlobalRole.PLAYER, GlobalRole.RESEARCHER,
                GlobalRole.ADMIN);
        var createdSession = auth.createSession(sam.userId(), samId, "Sam", allRoles,
                nowRef.get().plusSeconds(600));
        String csrf = createdSession.record().csrfToken();
        assertFalse(csrf.contains("+") || csrf.contains("/") || csrf.contains("="),
                "CSRF token must use unpadded URL-safe Base64: " + csrf);
        byte[] sessionDigest = createdSession.record().sessionSha256();
        for (int i = 0; i < 8; i++) {
            var loaded = auth.findActiveSessionBySha256(sessionDigest).orElseThrow();
            assertEquals(allRoles, loaded.grantedRoles(),
                    "text[] role round-trip must stay stable across server-prepared runs (#"
                            + i + ")");
            assertEquals(csrf, loaded.csrfToken());
        }
        var expiring = auth.createSession(sam.userId(), samId, "Sam", Set.of(GlobalRole.PLAYER),
                nowRef.get().plusSeconds(10));
        nowRef.set(nowRef.get().plusSeconds(11));
        assertTrue(auth.findActiveSessionBySha256(expiring.record().sessionSha256()).isEmpty(),
                "expired session must not authenticate");
        auth.revokeSession(sessionDigest);
        assertTrue(auth.findActiveSessionBySha256(sessionDigest).isEmpty(),
                "revoked session must be deleted");

        // (d) Scoped API tokens: owner/seat scoping, live suspension, and revoked flag parity.
        byte[] tokenDigest = new byte[32];
        tokenDigest[0] = 0x5A;
        var createdToken = auth.createToken(sam.userId(), "wilds:seat", 7L, "bot-key",
                "iw_bot_abcdefgh_secret", "abcdefgh", tokenDigest);
        ScopedTokenRecord liveToken = auth.findActiveTokenBySha256(tokenDigest).orElseThrow();
        assertFalse(liveToken.revoked());
        assertFalse(liveToken.ownerSuspended());
        assertEquals(7L, liveToken.scopedResourceId());

        assertFalse(auth.revokeToken(u1.userId(), "wilds:seat", 7L, createdToken.tokenId()),
                "foreign owner cannot revoke token");
        assertFalse(auth.revokeToken(sam.userId(), "wilds:seat", 99L, createdToken.tokenId()),
                "wrong seat scope cannot revoke token");

        auth.suspendUser(sam.userId(), "audit test");
        ScopedTokenRecord suspendedToken = auth.findActiveTokenBySha256(tokenDigest).orElseThrow();
        assertTrue(suspendedToken.ownerSuspended(),
                "owner suspension is reflected immediately on token lookup");
        AuthRejectException blockedSession = assertThrows(AuthRejectException.class,
                () -> auth.createSession(sam.userId(), samId, "Sam", Set.of(GlobalRole.PLAYER),
                        nowRef.get().plusSeconds(60)));
        assertEquals(AuthErrorCode.ACCOUNT_SUSPENDED, blockedSession.code());

        assertTrue(auth.revokeToken(sam.userId(), "wilds:seat", 7L, createdToken.tokenId()));
        ScopedTokenRecord revokedToken = auth.findActiveTokenBySha256(tokenDigest).orElseThrow();
        assertTrue(revokedToken.revoked(),
                "PostgresAuthStore must return revoked=true record matching AuthStorePort contract");
    }

    @Test
    void worldEventPayloadCodecRoundTripsAllSixEventVariantsAndRejectsIntOverflow() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldId = store.ensureActiveWorld(5L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION).worldId();

        OwnerId owner = new OwnerId(0, 1);
        ActorId actor = new ActorId(0, 1);
        PlaceId place = new PlaceId(1, 1);
        TaskSpec waitSpec = new TaskSpec.Wait(2_500L, 3);
        TaskSpec travelSpec = new TaskSpec.Travel(place);

        List<WorldEvent> variants = List.of(
                new WorldEvent.OwnerRegistered(owner, actor, 1L, 0L, owner,
                        "Seat 'Sam' entered the world", "Owner own:0:1 registered"),
                new WorldEvent.TaskStarted(actor,
                        new ActiveTask(10L, 2_510L, 4L, waitSpec, 3),
                        2L, 10L, owner, null, "Started wait"),
                new WorldEvent.TaskBatchCompleted(actor, travelSpec, 0, 3L, 2_510L, owner,
                        null, "Completed travel"),
                new WorldEvent.TaskCancelled(actor, true, 2, 4L, 3_000L, owner,
                        null, "Cancelled active + 2 pending"),
                new WorldEvent.TaskFailed(actor, travelSpec, ErrorCode.NO_ROUTE, 5L, 3_500L,
                        owner, null, "Failed travel: NO_ROUTE"),
                new WorldEvent.PlaceDiscovered(place, actor, 6L, 3_500L, owner,
                        null, "Discovered plc:1:1"));

        List<StoredWorldEvent> stored = variants.stream()
                .map(ev -> StoredWorldEvent.of(ev, 1L))
                .toList();
        store.appendEvents(worldId, stored);

        List<StoredWorldEvent> roundTripped = store.readEventsSince(worldId, 1L, 0L, 100);
        assertEquals(variants.size(), roundTripped.size());
        for (int i = 0; i < variants.size(); i++) {
            assertEquals(variants.get(i), roundTripped.get(i).event(),
                    "variant #" + i + " must round-trip losslessly through JSONB");
        }

        // Narrowing-cast guard: > Integer.MAX_VALUE integer fields must fail fast.
        assertThrows(IllegalArgumentException.class, () -> WorldEventPayloadCodec.decode(
                "task_cancelled", 7L, 4_000L, null, "bad",
                "{\"actorId\":\"act:0:1\",\"cancelledActive\":false,"
                        + "\"clearedPendingCount\":5000000000,\"scopeOwner\":\"own:0:1\"}"));
    }

    // ------------------------------------------------------------------ helpers

    private static Instant readLastSeen(Jdbi jdbi, UserId userId) {
        try (var handle = jdbi.open()) {
            return handle.select("SELECT last_seen_at FROM auth.users WHERE user_id = ?",
                            userId.value())
                    .map((rs, _) -> rs.getObject("last_seen_at", OffsetDateTime.class).toInstant())
                    .one();
        }
    }

    private static long count(org.jdbi.v3.core.Handle handle, String sql) {
        return handle.select(sql).mapTo(long.class).one();
    }

}
