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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Zero-framework forward migrator: applies plain versioned {@code V###__*.sql}
 * resources under {@code db/} in ascending numeric order, one transaction per
 * script, guarded by a session {@code pg_advisory_lock} so concurrent boots
 * serialize. Refuses to boot when the database holds a migration newer than
 * this binary knows (an older binary must never silently run against a
 * newer schema), on duplicate version numbers, or on out-of-order gaps.
 */
public final class SqlMigrator {

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final long MIGRATION_LOCK_KEY = 0x6B77_5A31L;

    private final Jdbi jdbi;
    private final ClassLoader classLoader;

    public SqlMigrator(Jdbi jdbi) {
        this(jdbi, SqlMigrator.class.getClassLoader());
    }

    public SqlMigrator(Jdbi jdbi, ClassLoader classLoader) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
    }

    /**
     * Applies every unapplied script.
     *
     * @return the number of migrations applied by this call (0 on an
     *         idempotent re-boot)
     * @throws IllegalStateException when the database is newer than this
     *                               binary, duplicate/out-of-order versions
     *                               exist, or a script fails mid-apply
     */
    public int migrate() {
        TreeMap<Integer, String> scripts = discoverScripts();
        int maxKnown = scripts.isEmpty() ? 0 : scripts.lastKey();
        try (var handle = jdbi.open()) {
            handle.execute("SET lock_timeout = '10s'");
            boolean locked = false;
            Throwable primary = null;
            try {
                handle.execute("SELECT pg_advisory_lock(?)", MIGRATION_LOCK_KEY);
                locked = true;
                handle.execute("CREATE TABLE IF NOT EXISTS public.schema_migrations ("
                        + "version INT PRIMARY KEY, filename TEXT NOT NULL, "
                        + "applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                Map<Integer, String> applied = new LinkedHashMap<>();
                handle.select("SELECT version, filename FROM public.schema_migrations"
                                + " ORDER BY version")
                        .map((rs, _) -> Map.entry(rs.getInt("version"), rs.getString("filename")))
                        .forEach(entry -> applied.put(entry.getKey(), entry.getValue()));
                int maxApplied = applied.keySet().stream().mapToInt(Integer::intValue)
                        .max().orElse(0);
                if (maxApplied > maxKnown) {
                    throw new IllegalStateException("database migration version " + maxApplied
                            + " is newer than this binary knows (max known " + maxKnown
                            + "); refusing to boot");
                }
                int appliedNow = 0;
                for (Map.Entry<Integer, String> script : scripts.entrySet()) {
                    int version = script.getKey();
                    String filename = script.getValue();
                    String recordedFilename = applied.get(version);
                    if (recordedFilename != null) {
                        if (!recordedFilename.equals(filename)) {
                            throw new IllegalStateException("migration V" + version
                                    + " filename mismatch: database recorded \""
                                    + recordedFilename + "\" but binary has \"" + filename + "\"");
                        }
                        continue;
                    }
                    if (version < maxApplied) {
                        throw new IllegalStateException("out-of-order unapplied migration V"
                                + version + " (" + filename + ") precedes already-applied V"
                                + maxApplied);
                    }
                    applyScript(handle, version, filename);
                    appliedNow++;
                }
                return appliedNow;
            } catch (RuntimeException | Error failure) {
                primary = failure;
                throw failure;
            } finally {
                boolean unlocked = !locked;
                if (locked) {
                    try {
                        Boolean released = handle.select(
                                        "SELECT pg_advisory_unlock(?)", MIGRATION_LOCK_KEY)
                                .mapTo(Boolean.class)
                                .one();
                        unlocked = Boolean.TRUE.equals(released);
                    } catch (RuntimeException unlockFailure) {
                        abortConnectionQuietly(handle);
                        if (primary != null) {
                            primary.addSuppressed(unlockFailure);
                        } else {
                            throw unlockFailure;
                        }
                    }
                }
                if (!unlocked) {
                    abortConnectionQuietly(handle);
                } else {
                    try {
                        handle.execute("RESET lock_timeout");
                    } catch (RuntimeException resetFailure) {
                        abortConnectionQuietly(handle);
                        if (primary != null) {
                            primary.addSuppressed(resetFailure);
                        } else {
                            throw resetFailure;
                        }
                    }
                }
            }
        }
    }

    private static void abortConnectionQuietly(Handle handle) {
        try {
            handle.getConnection().abort(Runnable::run);
        } catch (SQLException | RuntimeException ignored) {
            // Best-effort eviction so a pooled connection never retains a lock or dirty session.
        }
    }

    private void applyScript(Handle handle, int version, String filename) {
        String sql = readResource("db/" + filename);
        Connection connection = handle.getConnection();
        boolean committed = false;
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
            committed = true;
        } catch (SQLException failure) {
            throw new IllegalStateException("migration V" + version + " (" + filename
                    + ") failed: " + failure.getMessage(), failure);
        } finally {
            if (!committed) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Rollback before setAutoCommit(true) prevents JDBC §10.1.1 implicit commit.
                }
            }
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // Session may already be gone; the pool discards it.
            }
        }
    }

    /** Version-sorted map of every {@code V###__*.sql} resource under {@code db/}. */
    private TreeMap<Integer, String> discoverScripts() {
        TreeMap<Integer, String> scripts = new TreeMap<>();
        try {
            Enumeration<URL> roots = classLoader.getResources("db");
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                String protocol = root.getProtocol();
                if ("file".equals(protocol)) {
                    try (var files = java.nio.file.Files.list(java.nio.file.Path.of(root.toURI()))) {
                        files.filter(java.nio.file.Files::isRegularFile)
                                .map(path -> path.getFileName().toString())
                                .sorted()
                                .forEach(name -> addIfVersioned(scripts, name));
                    }
                } else if ("jar".equals(protocol)) {
                    String rawSpec = root.toURI().getRawSchemeSpecificPart();
                    int bang = rawSpec.indexOf("!/");
                    java.nio.file.Path jarPath = java.nio.file.Path.of(
                            URI.create(bang >= 0 ? rawSpec.substring(0, bang) : rawSpec));
                    try (var jar = new java.util.jar.JarFile(jarPath.toFile())) {
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
        if (name.indexOf('/') >= 0) {
            return;
        }
        Matcher versioned = VERSIONED.matcher(name);
        if (versioned.matches()) {
            int version = Integer.parseInt(versioned.group(1));
            String existing = scripts.putIfAbsent(version, name);
            if (existing != null && !existing.equals(name)) {
                throw new IllegalStateException("duplicate migration version V" + version
                        + ": \"" + existing + "\" and \"" + name + "\"");
            }
        }
    }

    private String readResource(String path) {
        try (var in = classLoader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("migration resource missing: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read migration resource: " + path, e);
        }
    }
}
