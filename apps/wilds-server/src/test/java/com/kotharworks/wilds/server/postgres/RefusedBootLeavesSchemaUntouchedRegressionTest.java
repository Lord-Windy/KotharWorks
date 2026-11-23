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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.migrationsSnapshot;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.postgresConfig;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.preTicket11MigrationLoader;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.worldsSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.server.Main;
import com.kotharworks.wilds.server.WildsServer;
import com.kotharworks.wilds.server.adapters.postgres.PreMigrationVersionGate;
import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;

/**
 * Regression for deep-review finding 11-H1 (ticket 181c8f17): a refused boot must not apply
 * pending migrations. A binary carrying a new migration, booted in {@code refuse} mode over a
 * world pinned by an older binary or {@code --seed}, used to apply the migration and only then
 * refuse, which locked out the older binary that the diagnostic's first remediation names.
 * {@code PreMigrationVersionGate} now refuses read-only before {@code SqlMigrator.migrate()}
 * (ticket 11 Review Focus 8: "zero writes to any schema").
 *
 * <p>"Legacy" databases are migrated by a pre-ticket-11 binary, simulated by a class loader
 * that only sees {@code V001..V003}
 * ({@link VersionGateFixtures#preTicket11MigrationLoader}).
 */
@Timeout(120)
class RefusedBootLeavesSchemaUntouchedRegressionTest {

    private static final long FP = RuleRegistry.minimalDefault().fingerprint();

    @Test
    void refusedBootOnLegacySchemaAppliesNoMigrationAndOlderBinaryStillBoots_11H1(
            @TempDir Path tempDir) throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = jdbi(db);
        try (URLClassLoader legacyLoader = preTicket11MigrationLoader(tempDir)) {
            assertEquals(3, new SqlMigrator(jdbi, legacyLoader).migrate());
            insertLegacyWorld(jdbi, 1L);
            List<String> migrationsBefore = migrationsSnapshot(jdbi);
            List<String> worldsBefore = worldsSnapshot(jdbi);
            assertEquals(3, migrationsBefore.size());
            assertEquals(1, worldsBefore.size());

            WorldVersionMismatchException refused = assertThrows(
                    WorldVersionMismatchException.class,
                    () -> new WildsServer(postgresConfig(db, 2L, OnVersionMismatch.REFUSE)));
            assertEquals(1L, refused.stored().worldSeed());
            assertEquals(2L, refused.running().worldSeed());
            assertNull(refused.stored().rulesFingerprint(),
                    "a V003 database has no fingerprint column to read");

            assertEquals(3, maxMigration(jdbi), "the refused boot applied no migration");
            assertEquals(migrationsBefore, migrationsSnapshot(jdbi),
                    "public.schema_migrations unchanged");
            assertFalse(hasFingerprintColumn(jdbi), "V004 DDL was not applied");
            assertEquals(worldsBefore, worldsSnapshot(jdbi), "wilds_meta.worlds unchanged");

            // Remediation 1 works: the older binary's migrator still accepts the database.
            assertEquals(0, assertDoesNotThrow(
                    () -> new SqlMigrator(jdbi, legacyLoader).migrate()));
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {
                "--storage", "postgres", "--jdbc-url", db.jdbcUrl(),
                "--db-user", db.username(), "--db-password", db.password(),
                "--port", "0", "--seed", "2"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(Main.EXIT_VERSION_MISMATCH, code, stderr);
        assertEquals(3, code, stderr);
        assertTrue(stderr.contains("worldSeed"), stderr);
        assertFalse(stderr.contains("\tat "), "no stack trace: " + stderr);
        assertEquals(3, maxMigration(jdbi), "Main's refused boot applied no migration either");
        assertFalse(hasFingerprintColumn(jdbi));
    }

    @Test
    void matchingLegacyWorldStillMigratesAndBoots_11H1(@TempDir Path tempDir) throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = jdbi(db);
        try (URLClassLoader legacyLoader = preTicket11MigrationLoader(tempDir)) {
            assertEquals(3, new SqlMigrator(jdbi, legacyLoader).migrate());
        }
        int legacyId = insertLegacyWorld(jdbi, 1L);

        try (WildsServer server = new WildsServer(postgresConfig(db, 1L, OnVersionMismatch.REFUSE))) {
            server.start();
            assertEquals(4, maxMigration(jdbi), "a matching boot applies V004");
            assertEquals(legacyId, server.worldHost().worldId());
            assertTrue(server.worldHost().archivedWorld().isEmpty());
            assertNull(server.worldHost().worldRecord().rulesFingerprint(),
                    "the legacy world keeps a NULL fingerprint");
        }
    }

    @Test
    void archiveModeStillMigratesThenArchives_11H1(@TempDir Path tempDir) throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = jdbi(db);
        try (URLClassLoader legacyLoader = preTicket11MigrationLoader(tempDir)) {
            assertEquals(3, new SqlMigrator(jdbi, legacyLoader).migrate());
        }
        int legacyId = insertLegacyWorld(jdbi, 1L);

        int newId;
        try (WildsServer server = new WildsServer(postgresConfig(db, 2L, OnVersionMismatch.ARCHIVE))) {
            newId = server.worldHost().worldId();
            assertEquals(legacyId, server.worldHost().archivedWorld().orElseThrow().worldId());
        }
        assertEquals(4, maxMigration(jdbi), "archive mode migrates before the gate archives");
        assertTrue(newId != legacyId, "a fresh world was started");
        assertEquals("ARCHIVED", jdbi.withHandle(handle -> handle.select(
                        "SELECT status FROM wilds_meta.worlds WHERE world_id = ?", legacyId)
                .mapTo(String.class).one()));
        List<Long> activeFingerprints = jdbi.withHandle(handle -> handle.select(
                        "SELECT rules_fingerprint FROM wilds_meta.worlds"
                                + " WHERE status = 'ACTIVE' AND world_id = ?", newId)
                .mapTo(Long.class).list());
        assertEquals(List.of(FP), activeFingerprints);
    }

    @Test
    void fingerprintMismatchOnCurrentSchemaIsRefusedByPreGate_11H1() {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = jdbi(db);
        new SqlMigrator(jdbi).migrate();
        int worldId = jdbi.withHandle(handle -> handle.select("INSERT INTO wilds_meta.worlds"
                        + " (title, world_seed, rules_version, engine_version, protocol_version,"
                        + " rules_fingerprint, jvm_version)"
                        + " VALUES ('Ironwood Wilds', 1, ?, ?, ?, ?, '25') RETURNING world_id",
                        RuleRegistry.WILDS_RULES_VERSION, WildsEngine.API_VERSION,
                        WorldHost.WILDS_PROTOCOL_VERSION, FP ^ 1L)
                .mapTo(int.class).one());
        List<String> migrationsBefore = migrationsSnapshot(jdbi);
        List<String> worldsBefore = worldsSnapshot(jdbi);

        WorldVersionMismatchException refused = assertThrows(
                WorldVersionMismatchException.class,
                () -> PreMigrationVersionGate.check(jdbi, WorldHost.runningPin(1L)));
        assertEquals(worldId, refused.storedWorldId());
        assertEquals(FP ^ 1L, refused.stored().rulesFingerprint());
        assertEquals(FP, refused.running().rulesFingerprint());
        assertEquals(migrationsBefore, migrationsSnapshot(jdbi));
        assertEquals(worldsBefore, worldsSnapshot(jdbi));

        jdbi.useHandle(handle -> handle.execute(
                "UPDATE wilds_meta.worlds SET rules_fingerprint = ? WHERE world_id = ?",
                FP, worldId));
        assertDoesNotThrow(() -> PreMigrationVersionGate.check(jdbi, WorldHost.runningPin(1L)),
                "a matching fingerprint passes");

        jdbi.useHandle(handle -> handle.execute(
                "UPDATE wilds_meta.worlds SET rules_fingerprint = NULL WHERE world_id = ?",
                worldId));
        assertDoesNotThrow(() -> PreMigrationVersionGate.check(jdbi, WorldHost.runningPin(1L)),
                "a legacy NULL fingerprint matches on the versions alone");
        assertThrows(WorldVersionMismatchException.class,
                () -> PreMigrationVersionGate.check(jdbi, WorldHost.runningPin(2L)),
                "a seed mismatch is still refused");
    }

    @Test
    void preGateOnEmptyDatabaseCreatesNothing_11H1() throws InterruptedException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = jdbi(db);

        assertDoesNotThrow(() -> PreMigrationVersionGate.check(jdbi, WorldHost.runningPin(1L)));

        boolean noMigrationsTable = jdbi.withHandle(handle -> handle.select(
                        "SELECT to_regclass('public.schema_migrations') IS NULL")
                .mapTo(boolean.class).one());
        assertTrue(noMigrationsTable, "public.schema_migrations was not created");
        boolean noWildsMeta = jdbi.withHandle(handle -> handle.select(
                        "SELECT to_regnamespace('wilds_meta') IS NULL")
                .mapTo(boolean.class).one());
        assertTrue(noWildsMeta, "schema wilds_meta was not created");
        awaitNoOtherConnections(jdbi);
    }

    // ------------------------------------------------------------------ rig

    private static Jdbi jdbi(SharedPostgresContainer.FreshDatabase db) {
        return Jdbi.create(db.jdbcUrl(), db.username(), db.password());
    }

    /** One ACTIVE world with the current versions, inserted without the V004 column. */
    private static int insertLegacyWorld(Jdbi jdbi, long seed) {
        return jdbi.withHandle(handle -> handle.select("INSERT INTO wilds_meta.worlds"
                        + " (title, world_seed, rules_version, engine_version, protocol_version,"
                        + " jvm_version) VALUES ('Ironwood Wilds', ?, ?, ?, ?, '25')"
                        + " RETURNING world_id", seed, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION)
                .mapTo(int.class).one());
    }

    private static int maxMigration(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT max(version) FROM public.schema_migrations")
                .mapTo(int.class).one());
    }

    private static boolean hasFingerprintColumn(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.select("SELECT EXISTS (SELECT 1"
                        + " FROM information_schema.columns WHERE table_schema = 'wilds_meta'"
                        + " AND table_name = 'worlds' AND column_name = 'rules_fingerprint')")
                .mapTo(boolean.class).one());
    }

    /** Polls until no connection other than the probe's own remains (30 s deadline). */
    private static void awaitNoOtherConnections(Jdbi jdbi) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long last;
        while ((last = jdbi.withHandle(handle -> handle.select("SELECT count(*)"
                        + " FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND pid <> pg_backend_pid()")
                .mapTo(long.class).one())) != 0L) {
            if (System.nanoTime() > deadline) {
                fail("still " + last + " connections left by the pre-migration gate after 30s");
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }
}
