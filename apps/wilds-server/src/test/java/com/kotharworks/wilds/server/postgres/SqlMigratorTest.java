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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Test;
import org.jdbi.v3.core.Jdbi;

import com.kotharworks.wilds.server.adapters.postgres.SqlMigrator;

/**
 * Ticket 08 acceptance (1): fresh schema creation applies V001..V003 exactly
 * once, a second boot re-runs zero migrations, and a database newer than the
 * binary is refused at boot.
 */
@Timeout(120)
class SqlMigratorTest {

    @Test
    void freshDatabaseAppliesAllThreeMigrations() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(3, new SqlMigrator(jdbi).migrate(),
                "V001..V003 must all apply on a fresh database");

        List<Integer> versions = new ArrayList<>();
        List<String> filenames = new ArrayList<>();
        List<String> tables = new ArrayList<>();
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
        }
        assertEquals(List.of(1, 2, 3), versions, "exactly V001..V003 recorded");
        assertEquals(List.of("V001__auth.sql", "V002__wilds_meta.sql", "V003__wilds_world.sql"),
                filenames, "filenames recorded verbatim");
        assertEquals(new TreeSet<>(List.of("api_tokens", "command_wal", "identity_links",
                        "seats", "users", "web_sessions", "world_checkpoints", "world_events",
                        "worlds")),
                new TreeSet<>(tables), "all nine tables exist across the three schemas");
    }

    @Test
    void secondBootRunsZeroMigrations() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(3, new SqlMigrator(jdbi).migrate(), "first boot applies all three");
        assertEquals(0, new SqlMigrator(jdbi).migrate(), "second boot is a no-op");
        try (Connection connection = DriverManager.getConnection(db.jdbcUrl(), db.username(),
                db.password());
                ResultSet rs = connection.createStatement().executeQuery(
                        "SELECT count(*) FROM public.schema_migrations")) {
            assertTrue(rs.next());
            assertEquals(3, rs.getInt(1), "row count unchanged by the re-boot");
        }
    }

    @Test
    void refusesToBootWhenDatabaseIsNewerThanBinary() throws SQLException {
        SharedPostgresContainer.FreshDatabase db = SharedPostgresContainer.freshDatabase();
        Jdbi jdbi = Jdbi.create(db.jdbcUrl(), db.username(), db.password());
        assertEquals(3, new SqlMigrator(jdbi).migrate());
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
        assertEquals(3, new SqlMigrator(seedJdbi).migrate());
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
}
