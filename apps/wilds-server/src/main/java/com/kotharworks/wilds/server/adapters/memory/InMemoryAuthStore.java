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
package com.kotharworks.wilds.server.adapters.memory;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.http.CookieCodec;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.TokenId;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.IdentityProvisioner;
import com.kotharworks.auth.spi.ScopedTokenRecord;
import com.kotharworks.auth.spi.SessionRecord;
import com.kotharworks.auth.spi.SessionStore;
import com.kotharworks.wilds.server.ports.AuthStorePort;
import com.kotharworks.wilds.server.ports.CreatedToken;
import com.kotharworks.wilds.server.ports.UserRecord;

/**
 * Thread-safe in-memory reference {@link AuthStorePort} that also serves as
 * the {@code libs/auth} host seams ({@link IdentityProvisioner},
 * {@link SessionStore}, and the token-digest seam via a method handle) — one
 * class, three seams.
 * Credentials exist only as Base64-encoded SHA-256 digest keys; there is no
 * positive authentication cache, so {@link #suspendUser} takes effect on the
 * very next lookup. Nothing here is durable: it is the fast Phase-0 adapter
 * that the Postgres implementation (Ticket 08) replaces shape-for-shape.
 */
public final class InMemoryAuthStore implements AuthStorePort, IdentityProvisioner, SessionStore {

    private static final Base64.Encoder B64 = Base64.getEncoder();

    private final Clock clock;
    private final SecureRandom random;
    private final AtomicLong seatTokenIds = new AtomicLong(1);
    private final Map<UUID, UserRecord> usersById = new ConcurrentHashMap<>();
    private final Map<ExternalIdentity, UUID> userIdsByIdentity = new ConcurrentHashMap<>();
    private final Map<String, SessionRecord> sessionsByDigest = new ConcurrentHashMap<>();
    private final Map<String, TokenRow> tokensByDigest = new ConcurrentHashMap<>();

    /** Test fault injection: when non-null, every write path throws it. */
    private volatile RuntimeException writeFault;

    private record TokenRow(long tokenId, UserId ownerUserId, String resourceNamespace,
            long scopedResourceId, String prefix, String label, AtomicBoolean revoked) {
    }

    /** Builds a store over an injected clock (session expiry, suspension stamps). */
    public InMemoryAuthStore(Clock clock, SecureRandom random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
    }

    /** Convenience constructor with a platform {@link SecureRandom}. */
    public InMemoryAuthStore(Clock clock) {
        this(clock, new SecureRandom());
    }

    /** Test hook: arms every write path to throw {@code fault}; {@code null} clears it. */
    public void setWriteFault(RuntimeException fault) {
        this.writeFault = fault;
    }

    private void checkWriteFault() {
        RuntimeException fault = writeFault;
        if (fault != null) {
            throw fault;
        }
    }

    // ------------------------------------------------------------------ users

    @Override
    public UserRecord upsertUser(ExternalIdentity identity, String displayName) {
        checkWriteFault();
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(displayName, "displayName");
        UUID uuid = userIdsByIdentity.computeIfAbsent(identity, _ -> UUID.randomUUID());
        UserRecord fresh = UserRecord.active(new UserId(uuid), displayName, null);
        return usersById.compute(uuid, (_, existing) -> {
            if (existing == null) {
                return fresh;
            }
            if (!existing.displayName().equals(displayName) && existing.suspendedAt() == null) {
                return new UserRecord(existing.userId(), displayName,
                        existing.maxSeatsOverride(), existing.suspendedAt(),
                        existing.suspendedReason());
            }
            return existing;
        });
    }

    @Override
    public Optional<UserRecord> findUser(UserId userId) {
        return Optional.ofNullable(usersById.get(userId.value()));
    }

    @Override
    public UserId provision(ExternalIdentity externalId, String displayName) {
        return upsertUser(externalId, displayName).userId();
    }

    @Override
    public void suspendUser(UserId userId, String reason) {
        checkWriteFault();
        Objects.requireNonNull(userId, "userId");
        UserRecord current = usersById.get(userId.value());
        if (current == null) {
            throw new IllegalArgumentException("unknown user: " + userId.value());
        }
        usersById.replace(userId.value(), new UserRecord(current.userId(), current.displayName(),
                current.maxSeatsOverride(), clock.instant(), reason));
    }

    // ------------------------------------------------------------------ sessions

    @Override
    public CreatedSession create(UserId userId, ExternalIdentity externalId, String displayName,
            Set<GlobalRole> grantedRoles, Instant expiresAt) {
        return createSession(userId, externalId, displayName, grantedRoles, expiresAt);
    }

    @Override
    public CreatedSession createSession(UserId userId, ExternalIdentity externalId,
            String displayName, Set<GlobalRole> grantedRoles, Instant expiresAt) {
        checkWriteFault();
        Objects.requireNonNull(userId, "userId");
        UserRecord user = usersById.get(userId.value());
        if (user != null && user.suspendedAt() != null) {
            throw new AuthRejectException(AuthErrorCode.ACCOUNT_SUSPENDED,
                    "account suspended: " + user.suspendedReason());
        }
        String rawToken = CookieCodec.newRandomToken();
        byte[] digest = CookieCodec.sha256Digest(rawToken);
        String csrfToken = newToken();
        SessionRecord record = new SessionRecord(digest, userId, externalId, displayName,
                grantedRoles, csrfToken, expiresAt);
        sessionsByDigest.put(B64.encodeToString(digest), record);
        return new CreatedSession(rawToken, record);
    }

    @Override
    public Optional<SessionRecord> findActiveSessionBySha256(byte[] sha256) {
        SessionRecord record = sessionsByDigest.get(B64.encodeToString(sha256));
        if (record == null || !record.expiresAt().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(record);
    }

    @Override
    public void revokeByDigest(byte[] sha256Digest) {
        checkWriteFault();
        sessionsByDigest.remove(B64.encodeToString(sha256Digest));
    }

    @Override
    public void revokeSession(byte[] sha256) {
        revokeByDigest(sha256);
    }

    // ------------------------------------------------------------------ scoped api tokens

    @Override
    public CreatedToken createToken(UserId owner, String resourceNamespace, long scopedResourceId,
            String label, String rawToken, String prefix, byte[] sha256) {
        checkWriteFault();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resourceNamespace, "resourceNamespace");
        Objects.requireNonNull(rawToken, "rawToken");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(sha256, "sha256");
        long tokenId = seatTokenIds.getAndIncrement();
        tokensByDigest.put(B64.encodeToString(sha256), new TokenRow(tokenId, owner,
                resourceNamespace, scopedResourceId, prefix, label, new AtomicBoolean(false)));
        return new CreatedToken(rawToken, tokenId, prefix, sha256);
    }

    @Override
    public Optional<ScopedTokenRecord> findActiveTokenBySha256(byte[] sha256) {
        TokenRow row = tokensByDigest.get(B64.encodeToString(sha256));
        if (row == null) {
            return Optional.empty();
        }
        boolean ownerSuspended = usersById.getOrDefault(row.ownerUserId().value(),
                UserRecord.active(row.ownerUserId(), "?", null)).suspendedAt() != null;
        return Optional.of(new ScopedTokenRecord(new TokenId(row.tokenId()), row.ownerUserId(),
                row.resourceNamespace(), row.scopedResourceId(), row.revoked().get(),
                ownerSuspended));
    }

    @Override
    public boolean revokeToken(UserId owner, String resourceNamespace, long scopedResourceId,
            long tokenId) {
        checkWriteFault();
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(resourceNamespace, "resourceNamespace");
        boolean matched = false;
        for (TokenRow row : tokensByDigest.values()) {
            if (row.tokenId() == tokenId
                    && row.ownerUserId().equals(owner)
                    && row.resourceNamespace().equals(resourceNamespace)
                    && row.scopedResourceId() == scopedResourceId) {
                row.revoked().set(true);
                matched = true;
            }
        }
        return matched;
    }

    // ------------------------------------------------------------------ helpers

    /** The current instant on the injected clock (test convenience). */
    public Instant now() {
        return clock.instant();
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Test/audit helper: number of live (non-revoked) tokens. */
    public int liveTokenCount() {
        return (int) tokensByDigest.values().stream().filter(row -> !row.revoked().get()).count();
    }

    /** Test/audit helper: all known users, ascending by user id. */
    public List<UserRecord> allUsers() {
        return usersById.values().stream()
                .sorted((a, b) -> a.userId().value().compareTo(b.userId().value()))
                .toList();
    }
}
