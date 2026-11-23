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
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.worldsSnapshot;

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
import java.util.concurrent.TimeUnit;
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
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException.Pin;
import com.kotharworks.wilds.server.ports.ActiveWorld;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;
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

    private static final long FP = RuleRegistry.minimalDefault().fingerprint();

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
        CountDownLatch ready = new CountDownLatch(logins);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Callable<UserRecord>> logins20 = new ArrayList<>(logins);
        for (int i = 0; i < logins; i++) {
            logins20.add(() -> {
                ready.countDown();
                startGate.await();
                return auth.upsertUser(identity, "Sam");
            });
        }
        Set<UUID> minted = new java.util.HashSet<>();
        List<Future<UserRecord>> futures = new ArrayList<>(logins);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Callable<UserRecord> login : logins20) {
                futures.add(executor.submit(login));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "all 20 logins parked at the gate");
            startGate.countDown();
            for (Future<UserRecord> future : futures) {
                minted.add(future.get().userId().value());
            }
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
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP);
            });
        }
        List<Future<WorldRecord>> futures;
        gate.countDown();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = executor.invokeAll(boots);
        }
        WorldRecord world = futures.getFirst().get();
        assertTrue(world.worldId() >= 1);
        assertEquals(FP, world.rulesFingerprint(), "a new world records the fingerprint");
        assertEquals(WorldRecord.ACTIVE, world.status());
        for (Future<WorldRecord> future : futures) {
            assertEquals(world, future.get(), "every concurrent boot resolves the same world row");
        }

        WorldRecord again = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP);
        assertEquals(world, again, "the same pin re-reads the same world row");

        WorldVersionMismatchException mismatch = assertThrows(
                WorldVersionMismatchException.class,
                () -> store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION + 1,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP),
                "a different version triple refuses to boot");
        assertEquals(world.worldId(), mismatch.storedWorldId());
        assertEquals(new Pin(7L, RuleRegistry.WILDS_RULES_VERSION, WildsEngine.API_VERSION,
                WorldHost.WILDS_PROTOCOL_VERSION, FP), mismatch.stored());
        assertEquals(new Pin(7L, RuleRegistry.WILDS_RULES_VERSION + 1, WildsEngine.API_VERSION,
                WorldHost.WILDS_PROTOCOL_VERSION, FP), mismatch.running());
    }

    // ------------------------------------------------------------------ version gate (Ticket 11)

    @Test
    void legacyNullFingerprintRowMatchesOnVersionsAndIsNotBackfilled() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        int legacyId = insertWorldRow(jdbi, 7L, RuleRegistry.WILDS_RULES_VERSION, null);
        PostgresWorldStore store = new PostgresWorldStore(jdbi);

        WorldRecord world = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP);
        assertEquals(legacyId, world.worldId());
        assertEquals(null, world.rulesFingerprint(), "a legacy row reports an unset fingerprint");
        ActiveWorld archiveMode = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP,
                OnVersionMismatch.ARCHIVE);
        assertFalse(archiveMode.rolledOver(), "a legacy match never rolls over");
        try (var handle = jdbi.open()) {
            assertEquals(1L, count(handle, "SELECT count(*) FROM wilds_meta.worlds"
                    + " WHERE rules_fingerprint IS NULL AND status = 'ACTIVE'"),
                    "no backfill: the legacy row keeps rules_fingerprint NULL");
            assertEquals(1L, count(handle, "SELECT count(*) FROM wilds_meta.worlds"));
        }

        // A legacy row still refuses a version mismatch.
        WorldVersionMismatchException refused = assertThrows(WorldVersionMismatchException.class,
                () -> store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION + 1,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP));
        assertEquals(null, refused.stored().rulesFingerprint());
        assertTrue(refused.diagnostic().contains("unset"), refused.diagnostic());

        // The full host boot works against the legacy row.
        WorldHost host = new WorldHost(store, new InMemoryEventBus(), 7L, 1L);
        try {
            assertEquals(legacyId, host.worldId());
            assertEquals(FP, host.rulesFingerprint());
        } finally {
            host.close();
        }
    }

    @Test
    void presentFingerprintMismatchIsRefusedAndRefuseWritesNothing() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldId = populatedWorld(jdbi, store, 7L, FP + 1);
        List<String> worldsBefore = worldsSnapshot(jdbi);
        List<Long> countsBefore = worldWorldCounts(jdbi);

        WorldVersionMismatchException fingerprint = assertThrows(
                WorldVersionMismatchException.class,
                () -> store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP),
                "a present stored fingerprint that differs is never ignored");
        assertEquals(worldId, fingerprint.storedWorldId());
        assertEquals(FP + 1, fingerprint.stored().rulesFingerprint());
        assertEquals(FP, fingerprint.running().rulesFingerprint());
        assertThrows(WorldVersionMismatchException.class,
                () -> store.ensureActiveWorld(8L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP + 1,
                        OnVersionMismatch.REFUSE));

        assertEquals(worldsBefore, worldsSnapshot(jdbi), "REFUSE leaves wilds_meta.worlds as-is");
        assertEquals(countsBefore, worldWorldCounts(jdbi), "REFUSE writes nothing to wilds_world");
    }

    @Test
    void archiveRetiresTheOldWorldAtomicallyAndKeepsItsRowsExportable(@TempDir Path tempDir)
            throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldA = populatedWorld(jdbi, store, 7L, FP);
        List<Long> countsBefore = worldWorldCounts(jdbi);
        StringWriter exportBefore = new StringWriter();
        store.exportWalJsonl(worldA, exportBefore);

        ActiveWorld rolled = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION + 1,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP + 1,
                OnVersionMismatch.ARCHIVE);
        assertTrue(rolled.rolledOver());
        assertEquals(worldA, rolled.archived().worldId());
        assertEquals(WorldRecord.ARCHIVED, rolled.archived().status());
        assertEquals(RuleRegistry.WILDS_RULES_VERSION, rolled.archived().rulesVersion());
        WorldRecord worldB = rolled.world();
        assertNotEquals(worldA, worldB.worldId());
        assertEquals(WorldRecord.ACTIVE, worldB.status());
        assertEquals(RuleRegistry.WILDS_RULES_VERSION + 1, worldB.rulesVersion());
        assertEquals(FP + 1, worldB.rulesFingerprint());

        try (var handle = jdbi.open()) {
            assertEquals(1L, count(handle, "SELECT count(*) FROM wilds_meta.worlds WHERE world_id = "
                    + worldA + " AND status = 'ARCHIVED' AND ended_at IS NOT NULL"),
                    "A is ARCHIVED with ended_at set");
            assertEquals(1L, count(handle, "SELECT count(*) FROM wilds_meta.worlds WHERE world_id = "
                    + worldB.worldId() + " AND status = 'ACTIVE' AND ended_at IS NULL"
                    + " AND rules_fingerprint = " + (FP + 1)), "B is ACTIVE with its fingerprint");
            assertEquals(1L, count(handle,
                    "SELECT count(*) FROM wilds_meta.worlds WHERE status = 'ACTIVE'"));
        }
        assertEquals(countsBefore, worldWorldCounts(jdbi),
                "archiving touches no wilds_world rows");

        StringWriter exportAfter = new StringWriter();
        store.exportWalJsonl(worldA, exportAfter);
        assertEquals(exportBefore.toString(), exportAfter.toString(),
                "the archived world exports byte-identically");
        assertEquals(new WalHeader(7L, RuleRegistry.WILDS_RULES_VERSION, WildsEngine.API_VERSION,
                FP).encode(), exportAfter.toString().lines().findFirst().orElseThrow());
        Path walPath = tempDir.resolve("archived.jsonl");
        Files.writeString(walPath, exportAfter.toString());
        StringWriter hOut = new StringWriter();
        StringWriter hErr = new StringWriter();
        assertEquals(0, HeadlessMain.execute(
                new String[] {"replay", "--seed", "7", "--wal", walPath.toString(), "--hash"},
                new PrintWriter(hOut), new PrintWriter(hErr)), hErr.toString());
        assertEquals(1L, store.appendClockAdvance(worldB.worldId(), 0L, null),
                "B starts its own dense wal_seq");
    }

    @Test
    void concurrentArchiveBootsArchiveTheOldWorldExactlyOnce() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldA = store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP).worldId();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<ActiveWorld>> boots = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            boots.add(() -> {
                ready.countDown();
                gate.await();
                return store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION + 1,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP + 1,
                        OnVersionMismatch.ARCHIVE);
            });
        }
        List<ActiveWorld> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ActiveWorld>> futures = new ArrayList<>();
            for (Callable<ActiveWorld> boot : boots) {
                futures.add(executor.submit(boot));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both boots parked at the gate");
            gate.countDown();
            for (Future<ActiveWorld> future : futures) {
                results.add(future.get());
            }
        }
        assertEquals(1L, results.stream().filter(ActiveWorld::rolledOver).count(),
                "exactly one boot archives: " + results);
        ActiveWorld winner = results.stream().filter(ActiveWorld::rolledOver).findFirst()
                .orElseThrow();
        ActiveWorld loser = results.stream().filter(r -> !r.rolledOver()).findFirst()
                .orElseThrow();
        assertEquals(worldA, winner.archived().worldId());
        assertEquals(winner.world(), loser.world(), "the other boot sees B as a plain match");
        try (var handle = jdbi.open()) {
            assertEquals(1L, count(handle,
                    "SELECT count(*) FROM wilds_meta.worlds WHERE status = 'ACTIVE'"));
            assertEquals(2L, count(handle, "SELECT count(*) FROM wilds_meta.worlds"));
            assertEquals(1L, count(handle, "SELECT count(*) FROM wilds_meta.worlds"
                    + " WHERE status = 'ARCHIVED' AND world_id = " + worldA));
        }
    }

    @Test
    void archiveWhoseInsertFailsRollsBackAndLeavesTheOldWorldActive() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldA = populatedWorld(jdbi, store, 7L, FP);
        // Stands in for the server dying between the ARCHIVED update and the new-world insert.
        jdbi.useHandle(handle -> handle.execute("ALTER TABLE wilds_meta.worlds ADD CONSTRAINT"
                + " test_reject_seed_99 CHECK (world_seed <> 99)"));
        List<String> worldsBefore = worldsSnapshot(jdbi);
        List<Long> countsBefore = worldWorldCounts(jdbi);

        assertThrows(RuntimeException.class, () -> store.ensureActiveWorld(99L,
                RuleRegistry.WILDS_RULES_VERSION, WildsEngine.API_VERSION,
                WorldHost.WILDS_PROTOCOL_VERSION, FP, OnVersionMismatch.ARCHIVE));

        assertEquals(worldsBefore, worldsSnapshot(jdbi),
                "the ARCHIVED update rolled back with the failed insert");
        assertEquals(countsBefore, worldWorldCounts(jdbi));
        assertEquals(worldA, store.ensureActiveWorld(7L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP).worldId(),
                "world A is still the active world");
    }

    /** Inserts an ACTIVE worlds row directly (a legacy or foreign-binary world). */
    private static int insertWorldRow(Jdbi jdbi, long seed, int rulesVersion, Long fingerprint) {
        return jdbi.withHandle(handle -> handle.select("INSERT INTO wilds_meta.worlds (title,"
                        + " world_seed, rules_version, engine_version, protocol_version,"
                        + " rules_fingerprint, jvm_version) VALUES ('Ironwood Wilds', ?, ?, ?, ?,"
                        + " ?, '25') RETURNING world_id", seed, rulesVersion,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, fingerprint)
                .mapTo(int.class).one());
    }

    /** A world with a seat registration, a clock anchor, an event and a checkpoint. */
    private static int populatedWorld(Jdbi jdbi, PostgresWorldStore store, long seed,
            long fingerprint) {
        int worldId = store.ensureActiveWorld(seed, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, fingerprint).worldId();
        PostgresAuthStore auth = new PostgresAuthStore(jdbi, Clock.systemUTC(),
                new SecureRandom());
        UserRecord user = auth.upsertUser(
                new ExternalIdentity("https://id.example:8443", "gate-" + worldId), "Sam");
        SeatRecord seat = store.createSeat(user.userId(), "Gate Seat " + worldId,
                ControllerKind.HUMAN);
        store.appendCommand(worldId, 0L, seat.seatId(), UUID.randomUUID(),
                new WorldCommand.RegisterOwner(seat.seatId(), "Sam", "Sam"), null);
        store.appendClockAdvance(worldId, 500L, null);
        store.appendEvents(worldId, List.of(StoredWorldEvent.of(new WorldEvent.OwnerRegistered(
                new OwnerId(0, 1), new ActorId(0, 1), 1L, 0L, new OwnerId(0, 1), "pub", "priv"),
                seat.seatId())));
        store.saveCheckpoint(worldId, 2L, 500L, 42L, "{\"tick\":1}");
        return worldId;
    }

    /** Row counts of command_wal, world_checkpoints and world_events (all worlds). */
    private static List<Long> worldWorldCounts(Jdbi jdbi) {
        try (var handle = jdbi.open()) {
            return List.of(
                    count(handle, "SELECT count(*) FROM wilds_world.command_wal"),
                    count(handle, "SELECT count(*) FROM wilds_world.world_checkpoints"),
                    count(handle, "SELECT count(*) FROM wilds_world.world_events"));
        }
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

        // Newest-tail query: when > limit events exist, readRecentEvents returns the
        // newest rows in ascending cursor order (never freezing on the oldest rows).
        List<StoredWorldEvent> extra = new ArrayList<>();
        for (long c = 10; c <= 70; c++) {
            WorldEvent.OwnerRegistered ev = new WorldEvent.OwnerRegistered(
                    new OwnerId(0, 1), new ActorId(0, 1), c, c * 100L, new OwnerId(0, 1),
                    "pub-" + c, "priv-" + c);
            extra.add(StoredWorldEvent.of(ev, seat.seatId()));
        }
        store.appendEvents(worldId, extra);
        List<StoredWorldEvent> recentSeat = store.readRecentEvents(worldId, seat.seatId(), 5);
        assertEquals(List.of(66L, 67L, 68L, 69L, 70L),
                recentSeat.stream().map(StoredWorldEvent::cursor).toList());
        List<StoredWorldEvent> recentPublic = store.readRecentEvents(worldId, null, 5);
        assertEquals(List.of(66L, 67L, 68L, 69L, 70L),
                recentPublic.stream().map(StoredWorldEvent::cursor).toList());
        assertTrue(recentPublic.stream().allMatch(row -> row.privateSummary() == null
                && row.event() == null && row.seatId() == null));
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
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP).worldId();

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
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP).worldId();
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
                WildsEngine.API_VERSION, FP).encode(),
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
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP).worldId();
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
    void worldEventsRoundTripAllSixVariantsThroughJsonb() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        PostgresWorldStore store = new PostgresWorldStore(jdbi);
        int worldId = store.ensureActiveWorld(5L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP).worldId();

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
