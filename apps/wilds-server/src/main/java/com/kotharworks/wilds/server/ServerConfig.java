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
package com.kotharworks.wilds.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.kotharworks.auth.AuthMode;

/**
 * Immutable server configuration parsed from CLI flags. Integer-only values
 * (the codebase is float-free): logical time advances by
 * {@code wallDeltaMs * timeScale} per pump tick.
 *
 * @param port                      bind port ({@code 0} = ephemeral; loopback bind only
 *                                  in dev-local mode)
 * @param worldSeed                 canonical world seed
 * @param timeScale                 logical-ms advanced per wall-ms
 * @param pumpIntervalMs            wall-time interval between pump ticks
 * @param authMode                  {@code DEV_LOCAL} or {@code OIDC}
 * @param publicOrigin              external origin (CSRF allow-list, OIDC redirect)
 * @param oidcIssuerUrl             OIDC issuer (required in OIDC mode)
 * @param oidcClientId              OIDC resource client id
 * @param oidcClientSecret          OIDC client secret
 * @param oidcAuthorizationEndpoint explicit authorization endpoint (no discovery client)
 * @param oidcTokenEndpoint         explicit token endpoint
 * @param oidcJwksUri               explicit JWKS URI
 * @param ratePerSec                per-seat sustained request rate
 * @param rateBurst                 per-seat burst headroom
 * @param bindAddress               bind address (loopback only in dev-local mode)
 * @param storage                   {@code MEMORY} or {@code POSTGRES} persistence
 * @param jdbcUrl                   PostgreSQL JDBC URL (required in POSTGRES mode)
 * @param dbUser                    PostgreSQL user (optional)
 * @param dbPassword                PostgreSQL password (optional)
 * @param checkpointIntervalMs      wall-time uptime cadence between checkpoints
 */
public record ServerConfig(int port, long worldSeed, long timeScale, long pumpIntervalMs,
        AuthMode authMode, String publicOrigin, String oidcIssuerUrl, String oidcClientId,
        String oidcClientSecret, String oidcAuthorizationEndpoint, String oidcTokenEndpoint,
        String oidcJwksUri, long ratePerSec, long rateBurst, String bindAddress,
        StorageMode storage, String jdbcUrl, String dbUser, String dbPassword,
        long checkpointIntervalMs) {

    /** Persistence backend selected by {@code --storage}. */
    public enum StorageMode {
        /** The in-memory reference adapters (Phase-0 default). */
        MEMORY,
        /** HikariCP + JDBI3 PostgreSQL adapters with forward SQL migrations. */
        POSTGRES
    }

    /** Builds the default configuration (port 8080, seed 1, dev-local auth). */
    public static ServerConfig defaults() {
        return new ServerConfig(8080, 1L, 1L, 1_000L, AuthMode.DEV_LOCAL,
                "http://127.0.0.1:8080", null, null, null, null, null, null, 10L, 20L,
                "127.0.0.1", StorageMode.MEMORY, null, null, null, 300_000L);
    }

    public ServerConfig {
        Objects.requireNonNull(authMode, "authMode");
        Objects.requireNonNull(publicOrigin, "publicOrigin");
        Objects.requireNonNull(bindAddress, "bindAddress");
        Objects.requireNonNull(storage, "storage");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
        if (timeScale < 1) {
            throw new IllegalArgumentException("timeScale must be >= 1: " + timeScale);
        }
        if (pumpIntervalMs < 1) {
            throw new IllegalArgumentException("pumpIntervalMs must be >= 1: " + pumpIntervalMs);
        }
        if (ratePerSec < 1) {
            throw new IllegalArgumentException("ratePerSec must be >= 1: " + ratePerSec);
        }
        if (rateBurst < 1) {
            throw new IllegalArgumentException("rateBurst must be >= 1: " + rateBurst);
        }
    }

    /**
     * Parses CLI flags in either {@code --flag value} or {@code --flag=value}
     * form. Unknown flags, missing values, and non-integer numbers throw
     * {@link IllegalArgumentException} (Main renders usage on stderr and exits 2).
     *
     * @param rawArgs raw {@code main} args, e.g. {@code --port 8080 --seed=7 --time-scale=20}
     */
    public static ServerConfig parse(String[] rawArgs) {
        List<String> expanded = new ArrayList<>(rawArgs.length * 2);
        for (String arg : rawArgs) {
            int eq = arg.startsWith("--") ? arg.indexOf('=') : -1;
            if (eq > 2) {
                expanded.add(arg.substring(0, eq));
                expanded.add(arg.substring(eq + 1));
            } else {
                expanded.add(arg);
            }
        }
        String[] args = expanded.toArray(String[]::new);

        int port = 8080;
        long worldSeed = 1L;
        long timeScale = 1L;
        long pumpIntervalMs = 1_000L;
        AuthMode authMode = AuthMode.DEV_LOCAL;
        String publicOrigin = null;
        String oidcIssuerUrl = null;
        String oidcClientId = null;
        String oidcClientSecret = null;
        String oidcAuthorizationEndpoint = null;
        String oidcTokenEndpoint = null;
        String oidcJwksUri = null;
        long ratePerSec = 10L;
        long rateBurst = 20L;
        String bindAddress = "127.0.0.1";
        StorageMode storage = StorageMode.MEMORY;
        String jdbcUrl = null;
        String dbUser = null;
        String dbPassword = null;
        long checkpointIntervalMs = 300_000L;

        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            switch (flag) {
                case "--port" -> port = parseInt(next(args, ++i, flag), flag);
                case "--seed" -> worldSeed = parseLong(next(args, ++i, flag), flag);
                case "--time-scale" -> timeScale = parseLong(next(args, ++i, flag), flag);
                case "--pump-interval-ms" -> pumpIntervalMs = parseLong(next(args, ++i, flag), flag);
                case "--auth-mode" -> authMode = parseAuthMode(next(args, ++i, flag));
                case "--public-origin" -> publicOrigin = next(args, ++i, flag);
                case "--oidc-issuer" -> oidcIssuerUrl = next(args, ++i, flag);
                case "--oidc-client-id" -> oidcClientId = next(args, ++i, flag);
                case "--oidc-client-secret" -> oidcClientSecret = next(args, ++i, flag);
                case "--oidc-authorization-endpoint" ->
                        oidcAuthorizationEndpoint = next(args, ++i, flag);
                case "--oidc-token-endpoint" -> oidcTokenEndpoint = next(args, ++i, flag);
                case "--oidc-jwks-uri" -> oidcJwksUri = next(args, ++i, flag);
                case "--rate-per-sec" -> ratePerSec = parseLong(next(args, ++i, flag), flag);
                case "--rate-burst" -> rateBurst = parseLong(next(args, ++i, flag), flag);
                case "--bind" -> bindAddress = next(args, ++i, flag);
                case "--storage" -> storage = parseStorage(next(args, ++i, flag));
                case "--jdbc-url" -> jdbcUrl = next(args, ++i, flag);
                case "--db-user" -> dbUser = next(args, ++i, flag);
                case "--db-password" -> dbPassword = next(args, ++i, flag);
                case "--checkpoint-interval-ms" ->
                        checkpointIntervalMs = parseLong(next(args, ++i, flag), flag);
                default -> throw new IllegalArgumentException("unknown flag: " + flag);
            }
        }
        String origin = publicOrigin != null ? publicOrigin : "http://127.0.0.1:" + port;
        return new ServerConfig(port, worldSeed, timeScale, pumpIntervalMs, authMode, origin,
                oidcIssuerUrl, oidcClientId, oidcClientSecret, oidcAuthorizationEndpoint,
                oidcTokenEndpoint, oidcJwksUri, ratePerSec, rateBurst, bindAddress, storage,
                jdbcUrl, dbUser, dbPassword, checkpointIntervalMs);
    }

    /** Validates the OIDC-mode and POSTGRES-storage required fields. */
    public ServerConfig validated() {
        if (storage == StorageMode.POSTGRES) {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("--storage=postgres requires --jdbc-url");
            }
        }
        if (authMode == AuthMode.OIDC) {
            requireOidc("oidcIssuerUrl", oidcIssuerUrl);
            requireOidc("oidcClientId", oidcClientId);
            requireOidc("oidcClientSecret", oidcClientSecret);
            requireOidc("oidcAuthorizationEndpoint", oidcAuthorizationEndpoint);
            requireOidc("oidcTokenEndpoint", oidcTokenEndpoint);
            requireOidc("oidcJwksUri", oidcJwksUri);
        }
        return this;
    }

    private static void requireOidc(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "--auth-mode=oidc requires --" + toFlag(name));
        }
    }

    private static String toFlag(String camel) {
        return "--" + camel.replaceAll("([A-Z])", "-$1").toLowerCase(java.util.Locale.ROOT);
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }

    private static int parseInt(String value, String flag) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer, got: " + value);
        }
    }

    private static long parseLong(String value, String flag) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(flag + " expects an integer, got: " + value);
        }
    }

    private static StorageMode parseStorage(String value) {
        return switch (value) {
            case "memory" -> StorageMode.MEMORY;
            case "postgres" -> StorageMode.POSTGRES;
            default -> throw new IllegalArgumentException(
                    "--storage expects memory|postgres, got: " + value);
        };
    }

    private static AuthMode parseAuthMode(String value) {
        return switch (value) {
            case "dev-local" -> AuthMode.DEV_LOCAL;
            case "oidc" -> AuthMode.OIDC;
            default -> throw new IllegalArgumentException(
                    "--auth-mode expects dev-local|oidc, got: " + value);
        };
    }
}
