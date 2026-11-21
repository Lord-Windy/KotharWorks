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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdbi.v3.core.Jdbi;

/**
 * Zero-framework forward migrator: applies plain versioned {@code V###__*.sql}
 * resources under {@code db/} in ascending numeric order, one transaction per
 * script, guarded by a session {@code pg_advisory_lock} so concurrent boots
 * serialize. Refuses to boot when the database holds a migration newer than
 * this binary knows (an older binary must never silently run against a
 * newer schema).
 */
public final class SqlMigrator {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final long MIGRATION_LOCK_KEY = 0x6B77_5A31L;

    private final Jdbi jdbi;

    public SqlMigrator(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Applies every unapplied script.
     *
     * @return the number of migrations applied by this call (0 on an
     *         idempotent re-boot)
     * @throws IllegalStateException when the database is newer than this
     *                               binary, or a script fails mid-apply
     */
    public int migrate() {
        TreeMap<Integer, String> scripts = discoverScripts();
        int maxKnown = scripts.isEmpty() ? 0 : scripts.lastKey();
        try (var handle = jdbi.open()) {
            handle.execute("SET lock_timeout = '10s'");
            handle.execute("SELECT pg_advisory_lock(?)", MIGRATION_LOCK_KEY);
            try {
                handle.execute("CREATE TABLE IF NOT EXISTS public.schema_migrations ("
                        + "version INT PRIMARY KEY, filename TEXT NOT NULL, "
                        + "applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                var applied = handle.select("SELECT version FROM public.schema_migrations")
                        .mapTo(Integer.class)
                        .list();
                int maxApplied = applied.stream().mapToInt(Integer::intValue).max().orElse(0);
                if (maxApplied > maxKnown) {
                    throw new IllegalStateException("database migration version " + maxApplied
                            + " is newer than this binary knows (max known " + maxKnown
                            + "); refusing to boot");
                }
                int appliedNow = 0;
                for (Map.Entry<Integer, String> script : scripts.entrySet()) {
                    int version = script.getKey();
                    if (applied.contains(version)) {
                        continue;
                    }
                    applyScript(handle, version, script.getValue());
                    appliedNow++;
                }
                return appliedNow;
            } finally {
                handle.execute("SELECT pg_advisory_unlock(?)", MIGRATION_LOCK_KEY);
            }
        }
    }

    private void applyScript(org.jdbi.v3.core.Handle handle, int version, String filename) {
        String sql = readResource("db/" + filename);
        Connection connection = handle.getConnection();
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO public.schema_migrations (version, filename) VALUES (?, ?)")) {
                insert.setInt(1, version);
                insert.setString(2, filename);
                insert.executeUpdate();
            }
            connection.commit();
        } catch (SQLException failure) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // the lock timeout / connection death path; rethrow the original
            }
            throw new IllegalStateException("migration V" + version + " (" + filename
                    + ") failed: " + failure.getMessage(), failure);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // session may already be gone; the pool discards it
            }
        }
    }

    /** Version-sorted map of every {@code V###__*.sql} resource under {@code db/}. */
    private TreeMap<Integer, String> discoverScripts() {
        TreeMap<Integer, String> scripts = new TreeMap<>();
        try {
            Enumeration<URL> roots = Thread.currentThread().getContextClassLoader()
                    .getResources("db");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                String protocol = root.getProtocol();
                if ("file".equals(protocol)) {
                    try (var files = java.nio.file.Files.list(java.nio.file.Path.of(root.toURI()))) {
                        files.map(path -> path.getFileName().toString())
                                .forEach(name -> addIfVersioned(scripts, name));
                    }
                } else if ("jar".equals(protocol)) {
                    try (var jar = new java.util.jar.JarFile(
                            root.getPath().substring("file:".length(),
                                    root.getPath().lastIndexOf('!')))) {
                        jar.entries().asIterator().forEachRemaining(entry -> {
                            String name = entry.getName();
                            if (name.startsWith("db/") && !entry.isDirectory()) {
                                addIfVersioned(scripts, name.substring("db/".length()));
                            }
                        });
                    }
                }
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new UncheckedIOException("cannot enumerate db/ migration resources",
                    e instanceof IOException io ? io : new IOException(e));
        }
        return scripts;
    }

    private static void addIfVersioned(Map<Integer, String> scripts, String name) {
        Matcher versioned = VERSIONED.matcher(name);
        if (versioned.matches()) {
            scripts.put(Integer.parseInt(versioned.group(1)), name);
        }
    }

    private static String readResource(String path) {
        try (var in = SqlMigrator.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("migration resource missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read migration resource: " + path, e);
        }
    }

    /** Visible for tests: the fixed advisory-lock key this migrator serializes on. */
    static Set<Integer> knownVersions(TreeMap<Integer, String> scripts) {
        return Set.copyOf(scripts.keySet());
    }
}
