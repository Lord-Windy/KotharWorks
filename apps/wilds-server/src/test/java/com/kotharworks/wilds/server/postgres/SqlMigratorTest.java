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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;

/**
 * Ticket 08 acceptance (1): fresh schema creation applies V001..V004 exactly
 * once (Ticket 11 V004 upgrades a V003 database in place), a second boot re-runs
 * zero migrations, a database newer than the binary is refused at boot, pooled connections do not leak session settings
 * or advisory locks, non-SQL throwables never auto-commit partial DDL, and
 * space-containing JAR paths and duplicate/out-of-order scripts are handled.
 */
@Timeout(120)
class SqlMigratorTest {

    @Test
    void freshDatabaseAppliesAllFourMigrations() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(4, new SqlMigrator(jdbi).migrate(),
                "V001..V004 must all apply on a fresh database");

        List<Integer> versions = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        List<String> tables = new ArrayList<>();
        List<String> eventIndexes = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password())) {
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT version, filename FROM public.schema_migrations ORDER BY version")) {
                while (rs.next()) {
                    versions.add(rs.getInt("version"));
                    filenames.add(rs.getString("filename"));
                }
            }
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema IN"
                            + " ('auth', 'wilds_meta', 'wilds_world') ORDER BY table_name")) {
                while (rs.next()) {
                    tables.add(rs.getString("table_name"));
                }
            }
            try (ResultSet rs = connection.createStatement().executeQuery(
                    "SELECT indexname FROM pg_indexes WHERE schemaname = 'wilds_world'"
                            + " AND tablename = 'world_events' ORDER BY indexname")) {
                while (rs.next()) {
                    eventIndexes.add(rs.getString("indexname"));
                }
            }
        }
        assertEquals(List.of(1, 2, 3, 4), versions, "exactly V001..V004 recorded");
        assertEquals(List.of("V001__auth.sql", "V002__wilds_meta.sql", "V003__wilds_world.sql",
                "V004__worlds_rules_fingerprint.sql"),
                filenames, "filenames recorded verbatim");
        assertEquals("bigint YES", worldsColumn(db, "rules_fingerprint"),
                "V004 adds a nullable BIGINT rules_fingerprint to wilds_meta.worlds");
        assertEquals(new TreeSet<>(List.of("api_tokens", "command_wal", "identity_links",
                        "seats", "users", "web_sessions", "world_checkpoints", "world_events",
                        "worlds")),
                new TreeSet<>(tables), "all nine tables exist across the three schemas");
        assertTrue(eventIndexes.contains("idx_world_events_seat_cursor"),
                "seat catch-up index must exist: " + eventIndexes);
        assertTrue(eventIndexes.contains("idx_world_events_public_cursor"),
                "public feed partial index must exist: " + eventIndexes);
    }

    @Test
    void secondBootRunsZeroMigrations() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(4, new SqlMigrator(jdbi).migrate(), "first boot applies all four");
        assertEquals(0, new SqlMigrator(jdbi).migrate(), "second boot is a no-op");
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT count(*) FROM public.schema_migrations")) {
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1), "row count unchanged by the re-boot");
        }
    }

    @Test
    void refusesToBootWhenDatabaseIsNewerThanBinary() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(4, new SqlMigrator(jdbi).migrate());
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password());
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO public.schema_migrations (version, filename)"
                                + " VALUES (999, 'V999__future.sql')")) {
            insert.executeUpdate();
        }
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new SqlMigrator(jdbi).migrate(), "an unknown future migration refuses boot");
        assertTrue(refused.getMessage().contains("999"),
                "message names the offending version: " + refused.getMessage());
    }

    @Test
    void deadConnectionDuringUnlockDoesNotMaskPrimaryMigrationFailure() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi seedJdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(4, new SqlMigrator(seedJdbi).migrate());
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password());
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO public.schema_migrations (version, filename)"
                                + " VALUES (999, 'V999__future.sql')")) {
            insert.executeUpdate();
        }

        Jdbi dyingUnlockJdbi = Jdbi.create(() -> {
            Connection real = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                    db.password());
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (_, method, args) -> {
                        if (args != null && args.length > 0 && args[0] instanceof String sql
                                && sql.contains("pg_advisory_unlock")) {
                            real.close();
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        });

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> new SqlMigrator(dyingUnlockJdbi).migrate(),
                "pg_advisory_unlock failure on a dead session must not mask the primary error");
        assertTrue(refused.getMessage().contains("999"),
                "primary diagnostic preserved: " + refused.getMessage());
        assertTrue(refused.getSuppressed().length >= 1,
                "unlock failure attached as suppressed rather than silently dropped");
    }

    @Test
    void pooledConnectionResetsLockTimeoutAndAbortsWhenUnlockThrows() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(db.jdbcUrl());
        hikari.setUsername(db.username());
        hikari.setPassword(db.password());
        hikari.setMaximumPoolSize(1);
        try (HikariDataSource pool = new HikariDataSource(hikari)) {
            Jdbi jdbi = Jdbi.create(pool);
            assertEquals(4, new SqlMigrator(jdbi).migrate());
            String lockTimeout = jdbi.withHandle(handle -> handle.select("SHOW lock_timeout")
                    .mapTo(String.class).one());
            assertEquals("0", lockTimeout,
                    "SqlMigrator must RESET lock_timeout before returning pooled connection");
        }

        // If pg_advisory_unlock throws while the physical socket is still open,
        // SqlMigrator must abort the connection so the session advisory lock drops.
        AtomicBoolean aborted = new AtomicBoolean(false);
        Jdbi stuckUnlockJdbi = Jdbi.create(() -> {
            Connection real = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                    db.password());
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (_, method, args) -> {
                        if ("abort".equals(method.getName())) {
                            aborted.set(true);
                            real.close();
                            return null;
                        }
                        if (args != null && args.length > 0 && args[0] instanceof String sql
                                && sql.contains("pg_advisory_unlock")) {
                            throw new SQLException("simulated query cancel on unlock");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        });
        assertThrows(RuntimeException.class, () -> new SqlMigrator(stuckUnlockJdbi).migrate());
        assertTrue(aborted.get(), "failed unlock must abort the connection to drop the lock");
    }

    @Test
    void runtimeExceptionBeforeCommitRollsBackInsteadOfAutoCommittingDdl() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi faultedJdbi = Jdbi.create(() -> {
            Connection real = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                    db.password());
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class},
                    (_, method, args) -> {
                        if ("prepareStatement".equals(method.getName())
                                && args != null && args.length > 0
                                && args[0] instanceof String sql
                                && sql.contains("INSERT INTO public.schema_migrations")) {
                            throw new IllegalStateException("simulated runtime fault after DDL");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        });

        assertThrows(IllegalStateException.class, () -> new SqlMigrator(faultedJdbi).migrate());
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'auth'")) {
            assertTrue(rs.next());
            assertEquals(0, rs.getInt(1),
                    "partial V001 DDL must roll back rather than auto-committing via setAutoCommit(true)");
        }
    }

    @Test
    void jarInPathWithSpacesAppliesAndDuplicateOrOutOfOrderScriptsFailFast(
            @TempDir Path tempDir) throws IOException, SQLException {
        // (a) JAR inside a directory containing spaces (%20 in jar:file:... URL).
        Path spacedDir = Files.createDirectories(tempDir.resolve("dir with spaces"));
        Path jarFile = spacedDir.resolve("migrations.jar");
        try (JarOutputStream jarOut = new JarOutputStream(Files.newOutputStream(jarFile))) {
            jarOut.putNextEntry(new JarEntry("db/"));
            jarOut.closeEntry();
            jarOut.putNextEntry(new JarEntry("db/V001__space_probe.sql"));
            jarOut.write("CREATE TABLE public.space_jar_probe (id INT PRIMARY KEY);"
                    .getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        SharedPostgresContainer.FreshDatabase jarDb = SharedPostgresContainer.freshDatabase();
        Jdbi jarJdbi = Jdbi.create(jarDb.jdbcUrl(), jarDb.username(), jarDb.password());
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {jarFile.toUri().toURL()}, null)) {
            assertEquals(1, new SqlMigrator(jarJdbi, loader).migrate());
        }
        try (Connection connection = DriverManager.getConnection(jarDb.jdbcUrl(),
                jarDb.username(), jarDb.password());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT count(*) FROM information_schema.tables"
                                + " WHERE table_schema = 'public'"
                                + " AND table_name = 'space_jar_probe'")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "JAR migration in spaced path must apply cleanly");
        }

        // (b) Duplicate migration numbers (V001__a.sql and V001__b.sql) fail fast.
        Path dupRoot = Files.createDirectories(tempDir.resolve("dup-root/db"));
        Files.writeString(dupRoot.resolve("V001__alpha.sql"), "SELECT 1;");
        Files.writeString(dupRoot.resolve("V001__beta.sql"), "SELECT 1;");
        try (URLClassLoader dupLoader = new URLClassLoader(
                new URL[] {tempDir.resolve("dup-root").toUri().toURL()}, null)) {
            IllegalStateException dup = assertThrows(IllegalStateException.class,
                    () -> new SqlMigrator(jarJdbi, dupLoader).migrate());
            assertTrue(dup.getMessage().contains("duplicate migration version V1"),
                    dup.getMessage());
        }

        // (c) Out-of-order unapplied migration (< maxApplied) fails fast.
        SharedPostgresContainer.FreshDatabase gapDb = SharedPostgresContainer.freshDatabase();
        Jdbi gapJdbi = Jdbi.create(gapDb.jdbcUrl(), gapDb.username(), gapDb.password());
        try (Connection connection = DriverManager.getConnection(gapDb.jdbcUrl(),
                gapDb.username(), gapDb.password());
                var stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE public.schema_migrations (version INT PRIMARY KEY,"
                    + " filename TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            stmt.execute("INSERT INTO public.schema_migrations (version, filename)"
                    + " VALUES (3, 'V003__wilds_world.sql')");
        }
        IllegalStateException gap = assertThrows(IllegalStateException.class,
                () -> new SqlMigrator(gapJdbi).migrate());
        assertTrue(gap.getMessage().contains("out-of-order"), gap.getMessage());
    }

    @Test
    void v004UpgradesAV003DatabaseLeavingExistingWorldsUnfingerprinted(@TempDir Path tempDir)
            throws IOException, SQLException {
        // A database migrated by a pre-ticket-11 binary (V001..V003 only) holding a world.
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        try (URLClassLoader legacyLoader =
                VersionGateFixtures.preTicket11MigrationLoader(tempDir)) {
            assertEquals(3, new SqlMigrator(jdbi, legacyLoader).migrate());
        }
        jdbi.useHandle(handle -> handle.execute("INSERT INTO wilds_meta.worlds (title,"
                + " world_seed, rules_version, engine_version, protocol_version, jvm_version)"
                + " VALUES ('Ironwood Wilds', 1, 1, 1, 1, '25')"));

        assertEquals(1, new SqlMigrator(jdbi).migrate(), "the current binary applies only V004");
        assertEquals("bigint YES", worldsColumn(db, "rules_fingerprint"));
        long unfingerprinted = jdbi.withHandle(handle -> handle.select(
                        "SELECT count(*) FROM wilds_meta.worlds WHERE rules_fingerprint IS NULL")
                .mapTo(long.class).one());
        assertEquals(1L, unfingerprinted, "the pre-existing world keeps a NULL fingerprint");
    }

    /** {@code "<data_type> <is_nullable>"} of a {@code wilds_meta.worlds} column. */
    private static String worldsColumn(SharedPostgresContainer.FreshDatabase db, String column)
            throws SQLException {
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password());
                PreparedStatement query = connection.prepareStatement(
                        "SELECT data_type, is_nullable FROM information_schema.columns"
                                + " WHERE table_schema = 'wilds_meta' AND table_name = 'worlds'"
                                + " AND column_name = ?")) {
            query.setString(1, column);
            try (ResultSet rs = query.executeQuery()) {
                assertTrue(rs.next(), "column wilds_meta.worlds." + column + " exists");
                return rs.getString("data_type") + " " + rs.getString("is_nullable");
            }
        }
    }
}
