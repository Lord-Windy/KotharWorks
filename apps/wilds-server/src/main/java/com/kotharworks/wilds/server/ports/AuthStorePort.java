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
package com.kotharworks.wilds.server.ports;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.IdentityProvisioner;
import com.kotharworks.auth.spi.SessionStore;

/**
 * Persistence port for the auth world: JIT user provisioning, BFF web sessions,
 * scoped {@code iw_bot_} API tokens, and account suspension. Implementations
 * must keep <b>no positive authentication cache</b>: every lookup reads
 * through, so {@link #suspendUser} takes effect on the very next request.
 *
 * <p>Credentials are persisted only as SHA-256 digests; raw session cookies and
 * token secrets are returned to the caller exactly once at creation time. The
 * port carries the {@code libs/auth} host seams ({@link IdentityProvisioner}
 * and {@link SessionStore}) as default bridges, so one store instance wires
 * every authenticator directly.
 */
public interface AuthStorePort extends IdentityProvisioner, SessionStore {

    /**
     * Returns the existing user for {@code identity} or provisions a fresh one
     * (idempotent per identity). Used on every verified login (JWT, OIDC
     * callback, dev persona switch).
     */
    UserRecord upsertUser(ExternalIdentity identity, String displayName);

    /** {@code IdentityProvisioner} seam: provisioning is the user upsert. */
    @Override
    default UserId provision(ExternalIdentity externalId, String displayName) {
        return upsertUser(externalId, displayName).userId();
    }

    /** The user row for {@code userId}, or empty when unknown. */
    Optional<UserRecord> findUser(UserId userId);

    /**
     * Creates a web session, persisting only the digest of the minted raw
     * cookie token.
     */
    SessionStore.CreatedSession createSession(UserId userId, ExternalIdentity identity,
            String displayName, Set<GlobalRole> grantedRoles, Instant expiresAt);

    /** {@code SessionStore} seam. */
    @Override
    default SessionStore.CreatedSession create(UserId userId, ExternalIdentity externalId,
            String displayName, Set<GlobalRole> grantedRoles, Instant expiresAt) {
        return createSession(userId, externalId, displayName, grantedRoles, expiresAt);
    }

    /** The session holding this digest, when present and not expired. */
    Optional<com.kotharworks.auth.spi.SessionRecord> findActiveSessionBySha256(byte[] sha256);

    /** {@code SessionStore} seam. */
    @Override
    default Optional<com.kotharworks.auth.spi.SessionRecord> findByDigest(byte[] sha256Digest) {
        return findActiveSessionBySha256(sha256Digest);
    }

    /** {@code SessionStore} seam (idempotent). */
    @Override
    default void revokeByDigest(byte[] sha256Digest) {
        revokeSession(sha256Digest);
    }

    /** Revokes the session holding this digest (idempotent). */
    void revokeSession(byte[] sha256);

    /**
     * Persists a caller-minted scoped token by digest. The caller (seat routes)
     * owns {@code ApiTokenCodec} minting; the store owns ids and persistence.
     *
     * @param resourceNamespace must be {@code "wilds:seat"} for this server
     * @param scopedResourceId  the seat id the token is scoped to
     * @param rawToken          full {@code iw_bot_...} wire form (shown once)
     * @param prefix            8-char display prefix
     * @param sha256            digest of {@code rawToken}
     */
    CreatedToken createToken(UserId owner, String resourceNamespace, long scopedResourceId,
            String label, String rawToken, String prefix, byte[] sha256);

    /**
     * The active token record for this digest, with {@code revoked} and
     * {@code ownerSuspended} computed live (fail-closed: a suspended owner's
     * tokens authenticate as suspended immediately). Also the
     * {@code ScopedTokenLookup} seam shape for
     * {@code ScopedApiTokenAuthenticator}.
     */
    Optional<com.kotharworks.auth.spi.ScopedTokenRecord> findActiveTokenBySha256(byte[] sha256);

    /** Revokes the token (idempotent; unknown ids are silently ignored). */
    void revokeToken(long tokenId);

    /**
     * Suspends the account: the next request authenticated as this user — JWT,
     * cookie, or {@code iw_bot_} token — is rejected immediately.
     */
    void suspendUser(UserId userId, String reason);
}
