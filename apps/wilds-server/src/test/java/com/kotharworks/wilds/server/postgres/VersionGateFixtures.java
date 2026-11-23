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

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jdbi.v3.core.Jdbi;

import com.kotharworks.auth.AuthMode;
import com.kotharworks.wilds.server.ServerConfig;
import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;

/**
 * Shared rig for the ticket 11 version-gate Postgres tests: the production-path server
 * config, row-level snapshots that prove "zero writes", and a class loader that plays a
 * pre-ticket-11 binary (migrations {@code V001..V003} only).
 */
final class VersionGateFixtures {

    private static final List<String> PRE_TICKET_11_SCRIPTS = List.of("V001__auth.sql",
            "V002__wilds_meta.sql", "V003__wilds_world.sql");

    private VersionGateFixtures() {
    }

    /**
     * Postgres-storage, dev-local config with checkpoints disabled, mirroring
     * {@code FullSpineIntegrationTest#boot}: the production pool + migrations path.
     */
    static ServerConfig postgresConfig(SharedPostgresContainer.FreshDatabase db, long seed,
            OnVersionMismatch onVersionMismatch) {
        return new ServerConfig(0, seed, 20L, 50L, AuthMode.DEV_LOCAL,
                "http://127.0.0.1:0", null, null, null, null, null, null, 100L, 200L,
                "127.0.0.1", ServerConfig.StorageMode.POSTGRES, db.jdbcUrl(), db.username(),
                db.password(), Long.MAX_VALUE, onVersionMismatch);
    }

    /** Every {@code wilds_meta.worlds} row rendered as JSON text, ascending by world_id. */
    static List<String> worldsSnapshot(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT row_to_json(w)::text FROM wilds_meta.worlds w ORDER BY world_id")
                .mapTo(String.class).list());
    }

    /** Every {@code public.schema_migrations} row rendered as JSON text, ascending by version. */
    static List<String> migrationsSnapshot(Jdbi jdbi) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT row_to_json(m)::text FROM (SELECT version, filename, applied_at"
                                + " FROM public.schema_migrations ORDER BY version) m")
                .mapTo(String.class).list());
    }

    /** A class loader that sees only the pre-ticket-11 migrations, as an older binary would. */
    static URLClassLoader preTicket11MigrationLoader(Path tempDir) throws IOException {
        Path legacyDb = Files.createDirectories(tempDir.resolve("legacy/db"));
        for (String script : PRE_TICKET_11_SCRIPTS) {
            try (InputStream in = SqlMigrator.class.getClassLoader()
                    .getResourceAsStream("db/" + script)) {
                if (in == null) {
                    fail("missing migration resource db/" + script);
                }
                Files.write(legacyDb.resolve(script), in.readAllBytes());
            }
        }
        return new URLClassLoader(new URL[] {tempDir.resolve("legacy").toUri().toURL()}, null);
    }
}
