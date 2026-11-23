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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.migrationsSnapshot;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.postgresConfig;
import static com.kotharworks.wilds.server.postgres.VersionGateFixtures.worldsSnapshot;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.rules.RulesFingerprint;
import com.kotharworks.wilds.server.Main;
import com.kotharworks.wilds.server.WildsServer;
import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;

/**
 * Ticket 11 §3.5 boot gate over the production composition root (own pool,
 * migrations, Postgres adapters): a stored world pinned by a different binary
 * refuses to boot with zero writes, {@code Main.run} exits {@code 3} with a
 * stack-trace-free diagnostic, and the pool closes so the process can exit
 * (Review Focus 8); a legacy {@code NULL}-fingerprint world still boots
 * (Review Focus 9).
 */
@Timeout(120)
class WorldVersionGateIntegrationTest {

    private static final long FP = RuleRegistry.minimalDefault().fingerprint();

    @Test
    void mismatchedWorldRefusesBootWithZeroWritesExitThreeAndAClosedPool() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        int storedRules = RuleRegistry.WILDS_RULES_VERSION + 1;
        jdbi.useHandle(handle -> handle.execute("INSERT INTO wilds_meta.worlds (title,"
                + " world_seed, rules_version, engine_version, protocol_version,"
                + " rules_fingerprint, jvm_version) VALUES ('Ironwood Wilds', 1, ?, ?, ?, ?, '25')",
                storedRules, WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION, FP));
        List<String> worldsBefore = worldsSnapshot(jdbi);
        assertEquals(1, worldsBefore.size());
        // 11-H1 (ticket 181c8f17): the refused boot must not touch public.schema_migrations
        // either; this database is fully migrated, so any new row or rewrite would be a write.
        List<String> migrationsBefore = migrationsSnapshot(jdbi);
        assertFalse(migrationsBefore.isEmpty());

        WorldVersionMismatchException refused = assertThrows(
                WorldVersionMismatchException.class,
                () -> new WildsServer(postgresConfig(db, 1L, OnVersionMismatch.REFUSE)),
                "the constructor surfaces the typed mismatch through the boot-failure cleanup");
        assertEquals(storedRules, refused.stored().rulesVersion());
        assertEquals(RuleRegistry.WILDS_RULES_VERSION, refused.running().rulesVersion());
        assertNoWrites(jdbi, worldsBefore, migrationsBefore);

        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = Main.run(new String[] {
                "--storage", "postgres", "--jdbc-url", db.jdbcUrl(),
                "--db-user", db.username(), "--db-password", db.password(), "--port", "0"},
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertEquals(3, code, stderr);
        assertEquals(Main.EXIT_VERSION_MISMATCH, code);
        assertTrue(stderr.startsWith("wilds-server: "), stderr);
        assertTrue(stderr.contains("rulesVersion"), stderr);
        assertTrue(stderr.contains(Integer.toString(storedRules)), stderr);
        assertTrue(stderr.contains(Integer.toString(RuleRegistry.WILDS_RULES_VERSION)), stderr);
        assertTrue(stderr.contains("--on-version-mismatch=archive"), stderr);
        assertTrue(stderr.contains(RulesFingerprint.toHex(FP)), stderr);
        assertFalse(stderr.contains("\tat "), "no stack trace: " + stderr);
        assertFalse(stderr.contains("Exception"), "no exception names: " + stderr);
        assertEquals("", out.toString(StandardCharsets.UTF_8), "no listen line");
        assertNoWrites(jdbi, worldsBefore, migrationsBefore);

        // Review Focus 8: every server connection is gone (the Hikari pool closed) ...
        awaitZero("server connections to the test database", () -> jdbi.withHandle(handle ->
                handle.select("SELECT count(*) FROM pg_stat_activity WHERE datname ="
                        + " current_database() AND pid <> pg_backend_pid()")
                        .mapTo(long.class).one()));
        // ... and no non-daemon platform thread started by the refused boot survives.
        awaitZero("non-daemon threads left by the refused boot", () ->
                lingeringNonDaemon(threadsBefore).size());
    }

    @Test
    void legacyNullFingerprintWorldBootsAndServesMeta() throws Exception {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        new SqlMigrator(jdbi).migrate();
        int legacyId = jdbi.withHandle(handle -> handle.select("INSERT INTO wilds_meta.worlds"
                        + " (title, world_seed, rules_version, engine_version, protocol_version,"
                        + " jvm_version) VALUES ('Ironwood Wilds', 1, ?, ?, ?, '25')"
                        + " RETURNING world_id", RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION, WorldHost.WILDS_PROTOCOL_VERSION)
                .mapTo(int.class).one());

        try (WildsServer server = new WildsServer(postgresConfig(db, 1L, OnVersionMismatch.REFUSE))) {
            server.start();
            assertEquals(legacyId, server.worldHost().worldId());
            assertTrue(server.worldHost().archivedWorld().isEmpty());
            assertNull(server.worldHost().worldRecord().rulesFingerprint());
            HttpResponse<String> meta = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                            + "/api/v1/meta")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, meta.statusCode(), meta.body());
            JsonCodec.JsonObj body = (JsonCodec.JsonObj) JsonCodec.parse(meta.body());
            assertEquals(legacyId, ((JsonCodec.JsonInt) body.getOrNull("worldId")).value());
            assertEquals(RulesFingerprint.toHex(FP),
                    ((JsonCodec.JsonStr) body.getOrNull("rulesFingerprint")).value(),
                    "meta reports the running binary's fingerprint");
        }
        long unfingerprinted = jdbi.withHandle(handle -> handle.select(
                        "SELECT count(*) FROM wilds_meta.worlds WHERE rules_fingerprint IS NULL"
                                + " AND status = 'ACTIVE'")
                .mapTo(long.class).one());
        assertEquals(1L, unfingerprinted, "the legacy row is not backfilled by a boot");
    }

    // ------------------------------------------------------------------ rig

    private static void assertNoWrites(Jdbi jdbi, List<String> worldsBefore,
            List<String> migrationsBefore) {
        assertEquals(worldsBefore, worldsSnapshot(jdbi), "wilds_meta.worlds unchanged");
        // 11-H1 (ticket 181c8f17): a refused boot applies no migration and rewrites no row.
        assertEquals(migrationsBefore, migrationsSnapshot(jdbi),
                "public.schema_migrations unchanged");
        for (String table : List.of("command_wal", "world_checkpoints", "world_events")) {
            long rows = jdbi.withHandle(handle -> handle.select(
                    "SELECT count(*) FROM wilds_world." + table).mapTo(long.class).one());
            assertEquals(0L, rows, "zero rows in wilds_world." + table);
        }
    }

    private static Set<String> lingeringNonDaemon(Set<Thread> before) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> !before.contains(thread) && thread.isAlive()
                        && !thread.isDaemon())
                .map(Thread::getName)
                .collect(Collectors.toSet());
    }

    /** Polls {@code probe} until it reads zero or a 30 s deadline passes. */
    private static void awaitZero(String what, LongSupplier probe) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long last;
        while ((last = probe.getAsLong()) != 0L) {
            if (System.nanoTime() > deadline) {
                fail("still " + last + " " + what + " after 30s");
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }
}
