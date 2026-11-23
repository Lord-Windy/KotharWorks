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
package com.kotharworks.wilds.server.adapters.postgres;

import java.util.Objects;
import java.util.Optional;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.wilds.server.domain.WorldVersionMismatchException;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException.Pin;

/**
 * Read-only version gate that runs <em>before</em> {@link SqlMigrator#migrate()} when the
 * server boots in {@code refuse} mode (ticket 11 Review Focus 8).
 *
 * <p>Why it exists: a binary that carries a new migration ({@code V004} today, {@code V005}
 * and later tomorrow) used to apply it and only then discover, in
 * {@code ensureActiveWorld}, that the stored world was pinned by an older binary or
 * {@code --seed}. The refused boot had already added DDL and a
 * {@code public.schema_migrations} row, so the older binary that the diagnostic tells the
 * operator to run would then refuse the database as "newer than this binary knows". This
 * pre-check refuses first, so a refused boot leaves the schema at the stored world's version
 * and the matching older binary can still boot.
 *
 * <p>It performs zero writes and zero DDL: one connection, one transaction opened with
 * {@code SET TRANSACTION READ ONLY} and always rolled back. It reads only the
 * {@code wilds_meta.worlds} columns that {@code V002} guarantees, plus
 * {@code rules_fingerprint} when {@code V004} has already added it. It never creates or reads
 * {@code public.schema_migrations} and never takes the migrator's advisory lock. A database
 * with no {@code wilds_meta.worlds} table, or with no {@code ACTIVE} row, passes: there is no
 * stored world to protect.
 *
 * <p>This check is advisory, not authoritative. The post-migration
 * {@code ensureActiveWorld} comparison, taken under {@code pg_advisory_xact_lock}, still
 * decides every boot: it handles a concurrent boot that creates or archives the active world
 * between this read and the migration, and it is the only gate in {@code archive} mode,
 * where this pre-check is skipped.
 */
public final class PreMigrationVersionGate {

    private PreMigrationVersionGate() {
    }

    /**
     * Refuses, without writing anything, when the stored active world does not admit the
     * {@code running} pin.
     *
     * @param jdbi    a JDBI over the target database (its connection is closed on return)
     * @param running the pin of the booting binary and {@code --seed}
     * @throws WorldVersionMismatchException when a stored {@code ACTIVE} world exists and
     *                                       {@code stored.admits(running)} is false
     */
    public static void check(Jdbi jdbi, Pin running) {
        Objects.requireNonNull(jdbi, "jdbi");
        Objects.requireNonNull(running, "running");
        Optional<StoredWorld> stored;
        try (Handle handle = jdbi.open()) {
            handle.begin();
            try {
                handle.execute("SET TRANSACTION READ ONLY");
                handle.execute("SET LOCAL statement_timeout = '10s'");
                stored = readActiveWorld(handle);
            } finally {
                handle.rollback();
            }
        }
        if (stored.isPresent() && !stored.get().pin().admits(running)) {
            throw new WorldVersionMismatchException(stored.get().worldId(), stored.get().pin(),
                    running);
        }
    }

    private record StoredWorld(int worldId, Pin pin) {
    }

    private static Optional<StoredWorld> readActiveWorld(Handle handle) {
        boolean worldsTableExists = handle.select(
                        "SELECT to_regclass('wilds_meta.worlds') IS NOT NULL")
                .mapTo(boolean.class).one();
        if (!worldsTableExists) {
            return Optional.empty();
        }
        boolean hasFingerprint = handle.select("SELECT EXISTS (SELECT 1"
                        + " FROM information_schema.columns WHERE table_schema = 'wilds_meta'"
                        + " AND table_name = 'worlds' AND column_name = 'rules_fingerprint')")
                .mapTo(boolean.class).one();
        String columns = "world_id, world_seed, rules_version, engine_version, protocol_version"
                + (hasFingerprint ? ", rules_fingerprint" : "");
        return handle.select("SELECT " + columns + " FROM wilds_meta.worlds"
                        + " WHERE status = 'ACTIVE' ORDER BY world_id DESC LIMIT 1")
                .map((rs, ctx) -> {
                    Long fingerprint = null;
                    if (hasFingerprint) {
                        long value = rs.getLong("rules_fingerprint");
                        fingerprint = rs.wasNull() ? null : value;
                    }
                    return new StoredWorld(rs.getInt("world_id"), new Pin(
                            rs.getLong("world_seed"), rs.getInt("rules_version"),
                            rs.getInt("engine_version"), rs.getInt("protocol_version"),
                            fingerprint));
                })
                .findFirst();
    }
}
