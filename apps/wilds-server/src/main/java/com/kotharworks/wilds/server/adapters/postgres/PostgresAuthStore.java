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

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.TokenId;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.ScopedTokenRecord;
import com.kotharworks.auth.spi.SessionStore;
import com.kotharworks.auth.spi.SessionRecord;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.CreatedToken;
import com.kotharworks.wilds.server.ports.UserRecord;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

/**
 * Durable {@link AuthStorePort} over PostgreSQL ({@code auth} schema) via the
 * JDBI3 Handle API. No positive authentication cache: every lookup reads
 * through, so {@link #suspendUser} bites on the next request.
 *
 * <p>Just-in-time provisioning takes a transaction-scoped advisory lock on
 * {@code hashtextextended(issuer || ':' || subject, 0)} before the
 * identity-link check and insert, so concurrent logins of one brand-new
 * identity mint exactly one {@code auth.users} row — no orphan rows, no
 * {@code 23505} races. Credentials persist only as SHA-256 digests.
 */
public final class PostgresAuthStore implements AuthStorePort {

    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();

    private final Jdbi jdbi;
    private final Clock clock;
    private final SecureRandom random;

    public PostgresAuthStore(Jdbi jdbi, Clock clock, SecureRandom random) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    // ------------------------------------------------------------------ users

    @Override
    public UserRecord upsertUser(ExternalIdentity identity, String displayName) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(displayName, "displayName");
        String lockKey = identity.issuer().length() + ":" + identity.issuer() + ":"
                + identity.subject();
        return jdbi.inTransaction(handle -> {
            handle.execute("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", lockKey);
            UUID existing = findUserId(handle, identity);
            UUID userId;
            if (existing == null) {
                userId = UUID.randomUUID();
                OffsetDateTime seenAt = now();
                handle.execute("INSERT INTO auth.users (user_id, display_name, created_at,"
                                + " last_seen_at) VALUES (?, ?, ?, ?)",
                        userId, displayName, seenAt, seenAt);
                handle.execute("INSERT INTO auth.identity_links (issuer, subject, user_id,"
                                + " linked_at) VALUES (?, ?, ?, ?)",
                        identity.issuer(), identity.subject(), userId, seenAt);
            } else {
                userId = existing;
                handle.execute("UPDATE auth.users SET display_name = ?, last_seen_at = ?"
                                + " WHERE user_id = ? AND suspended_at IS NULL",
                        displayName, now(), userId);
            }
            return selectUser(handle, userId);
        });
    }

    @Override
    public Optional<UserRecord> findUser(UserId userId) {
        return jdbi.withHandle(handle -> Optional.ofNullable(
                handle.select("SELECT user_id, display_name, max_seats_override, suspended_at,"
                                + " suspended_reason FROM auth.users WHERE user_id = ?",
                        userId.value())
                        .map((rs, ctx) -> userRow(rs))
                        .findFirst().orElse(null)));
    }

    @Override
    public void suspendUser(UserId userId, String reason) {
        Objects.requireNonNull(userId, "userId");
        int updated = jdbi.withHandle(handle -> handle.execute(
                "UPDATE auth.users SET suspended_at = ?, suspended_reason = ? WHERE user_id = ?",
                now(), reason, userId.value()));
        if (updated == 0) {
            throw new IllegalArgumentException("unknown user: " + userId.value());
        }
    }

    // ------------------------------------------------------------------ sessions

    @Override
    public SessionStore.CreatedSession createSession(UserId userId, ExternalIdentity identity,
            String displayName, Set<GlobalRole> grantedRoles, Instant expiresAt) {
        Objects.requireNonNull(userId, "userId");
        UserRecord user = findUser(userId).orElse(null);
        if (user != null && user.suspendedAt() != null) {
            throw new AuthRejectException(AuthErrorCode.ACCOUNT_SUSPENDED,
                    "account suspended: " + user.suspendedReason());
        }
        String rawToken = CookieCodec.newRandomToken();
        byte[] digest = CookieCodec.sha256Digest(rawToken);
        byte[] csrfBytes = new byte[32];
        random.nextBytes(csrfBytes);
        String csrfToken = B64_URL.encodeToString(csrfBytes);
        jdbi.useHandle(handle -> handle.execute(
                "INSERT INTO auth.web_sessions (session_sha256, user_id, issuer, subject,"
                        + " granted_roles, csrf_token, expires_at) VALUES (?, ?, ?, ?, ?::text[], ?, ?)",
                digest, userId.value(), identity.issuer(), identity.subject(),
                textArray(grantedRoles), csrfToken, at(expiresAt)));
        SessionRecord record = new SessionRecord(digest, userId, identity, displayName,
                grantedRoles, csrfToken, expiresAt);
        return new SessionStore.CreatedSession(rawToken, record);
    }

    @Override
    public Optional<SessionRecord> findActiveSessionBySha256(byte[] sha256) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT s.user_id, s.issuer, s.subject, s.granted_roles, s.csrf_token,"
                                + " s.expires_at, u.display_name FROM auth.web_sessions s"
                                + " JOIN auth.users u ON u.user_id = s.user_id"
                                + " WHERE s.session_sha256 = ? AND s.expires_at > ?",
                        sha256, now())
                .map((rs, ctx) -> {
                    UserId userId = new UserId(rs.getObject("user_id", UUID.class));
                    ExternalIdentity identity = new ExternalIdentity(rs.getString("issuer"),
                            rs.getString("subject"));
                    Set<GlobalRole> roles = parseRoles(rs.getArray("granted_roles"));
                    return new SessionRecord(sha256, userId, identity,
                            rs.getString("display_name"), roles, rs.getString("csrf_token"),
                            instantOf(rs, "expires_at"));
                })
                .findFirst());
    }

    @Override
    public void revokeSession(byte[] sha256) {
        jdbi.useHandle(handle -> handle.execute(
                "DELETE FROM auth.web_sessions WHERE session_sha256 = ?", sha256));
    }

    // ------------------------------------------------------------------ scoped api tokens

    @Override
    public CreatedToken createToken(UserId owner, String resourceNamespace, long scopedResourceId,
            String label, String rawToken, String prefix, byte[] sha256) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resourceNamespace, "resourceNamespace");
        Objects.requireNonNull(rawToken, "rawToken");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(sha256, "sha256");
        long tokenId = jdbi.withHandle(handle -> handle.select(
                        "INSERT INTO auth.api_tokens (owner_user_id, resource_namespace,"
                                + " scoped_resource_id, token_prefix, token_sha256, label)"
                                + " VALUES (?, ?, ?, ?, ?, ?) RETURNING token_id",
                        owner.value(), resourceNamespace, scopedResourceId, prefix, sha256, label)
                .mapTo(long.class).one());
        return new CreatedToken(rawToken, tokenId, prefix, sha256);
    }

    @Override
    public Optional<ScopedTokenRecord> findActiveTokenBySha256(byte[] sha256) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT t.token_id, t.owner_user_id, t.resource_namespace,"
                                + " t.scoped_resource_id,"
                                + " (t.revoked_at IS NOT NULL) AS token_revoked,"
                                + " (u.suspended_at IS NOT NULL) AS owner_suspended"
                                + " FROM auth.api_tokens t"
                                + " JOIN auth.users u ON u.user_id = t.owner_user_id"
                                + " WHERE t.token_sha256 = ?",
                        sha256)
                .map((rs, ctx) -> new ScopedTokenRecord(
                        new TokenId(rs.getLong("token_id")),
                        new UserId(rs.getObject("owner_user_id", UUID.class)),
                        rs.getString("resource_namespace"), rs.getLong("scoped_resource_id"),
                        rs.getBoolean("token_revoked"), rs.getBoolean("owner_suspended")))
                .findFirst());
    }

    @Override
    public boolean revokeToken(UserId owner, String resourceNamespace, long scopedResourceId,
            long tokenId) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resourceNamespace, "resourceNamespace");
        int matched = jdbi.withHandle(handle -> handle.execute(
                "UPDATE auth.api_tokens SET revoked_at = COALESCE(revoked_at, ?)"
                        + " WHERE token_id = ? AND owner_user_id = ?"
                        + " AND resource_namespace = ? AND scoped_resource_id = ?",
                now(), tokenId, owner.value(), resourceNamespace, scopedResourceId));
        return matched > 0;
    }

    // ------------------------------------------------------------------ row helpers

    private UUID findUserId(Handle handle, ExternalIdentity identity) {
        return handle.select("SELECT user_id FROM auth.identity_links WHERE issuer = ?"
                        + " AND subject = ?", identity.issuer(), identity.subject())
                .mapTo(UUID.class).findFirst().orElse(null);
    }

    private UserRecord selectUser(Handle handle, UUID userId) {
        return handle.select("SELECT user_id, display_name, max_seats_override, suspended_at,"
                        + " suspended_reason FROM auth.users WHERE user_id = ?", userId)
                .map((rs, ctx) -> userRow(rs)).findFirst().orElseThrow(
                        () -> new IllegalStateException("identity link without user row"));
    }

    private static UserRecord userRow(ResultSet rs) throws SQLException {
        Integer maxSeatsOverride = rs.getObject("max_seats_override", Integer.class);
        return new UserRecord(new UserId(rs.getObject("user_id", UUID.class)),
                rs.getString("display_name"), maxSeatsOverride,
                instantOf(rs, "suspended_at"),
                rs.getString("suspended_reason"));
    }

    /**
     * Rebuilds the granted-role set from the stored {@code text[]} column.
     * Read via {@link java.sql.Array#getArray} (never {@code getString}, whose
     * binary-protocol rendering quotes elements once pgjdbc switches the
     * statement to server-side prepare after five executions).
     */
    private static Set<GlobalRole> parseRoles(java.sql.Array column) throws SQLException {
        String[] names;
        try {
            names = (String[]) column.getArray();
        } finally {
            column.free();
        }
        Set<GlobalRole> roles = new LinkedHashSet<>();
        for (String name : names) {
            try {
                roles.add(GlobalRole.valueOf(name));
            } catch (IllegalArgumentException unknownRole) {
                throw new IllegalArgumentException(
                        "stored session carries unknown role: \"" + name + "\"");
            }
        }
        return Set.copyOf(roles);
    }

    /** The stored {@code text[]} literal for a role set (enum names only, no quoting). */
    private static String textArray(Set<GlobalRole> roles) {
        StringBuilder literal = new StringBuilder("{");
        List<String> names = roles.stream().map(GlobalRole::name).toList();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(names.get(i));
        }
        return literal.append('}').toString();
    }

    private OffsetDateTime now() {
        return at(clock.instant());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

}
