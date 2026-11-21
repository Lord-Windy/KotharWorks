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

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Singleton PostgreSQL container started once per test JVM so the whole
 * Postgres suite runs in a couple of seconds. {@link #freshDatabase()} mints a
 * throwaway database per test, giving every test clean migration state in
 * seconds. Deliberately avoids the junit-jupiter annotations to keep the
 * Testcontainers API surface minimal.
 */
final class SharedPostgresContainer {

    /** One fresh database over the shared container instance. */
    record FreshDatabase(String jdbcUrl, String username, String password) {
    }

    private static final PostgreSQLContainer CONTAINER =
            new PostgreSQLContainer("postgres:17-alpine");

    private static volatile boolean started;

    private SharedPostgresContainer() {
    }

    /** Starts the singleton (once) and creates a fresh throwaway database. */
    static synchronized FreshDatabase freshDatabase() {
        if (!started) {
            CONTAINER.start();
            started = true;
        }
        String database = "wilds_test_"
                + HexFormat.of().formatHex(randomName());
        // create on the cluster maintenance database, then point the returned
        // URL at the fresh database
        String adminUrl = CONTAINER.getJdbcUrl().replace(
                "/" + CONTAINER.getDatabaseName(), "/postgres");
        try (Connection connection = java.sql.DriverManager.getConnection(adminUrl,
                CONTAINER.getUsername(), CONTAINER.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create test database " + database, e);
        }
        String freshUrl = CONTAINER.getJdbcUrl().replace(
                "/" + CONTAINER.getDatabaseName(), "/" + database);
        return new FreshDatabase(freshUrl, CONTAINER.getUsername(), CONTAINER.getPassword());
    }

    private static byte[] randomName() {
        byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
