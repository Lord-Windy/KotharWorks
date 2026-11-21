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
package com.kotharworks.auth.spi;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.UserId;

/**
 * Host SPI: persistence for BFF session cookies (in-memory or Postgres).
 * Stores only digests; raw cookie tokens are returned once at creation time
 * and exist nowhere else.
 */
public interface SessionStore {

    /** A freshly created session: the raw token (shown once) plus its stored record. */
    record CreatedSession(String rawSessionToken, SessionRecord record) {
    }

    /**
     * Creates a session for the user, minting a fresh raw cookie token and
     * per-session CSRF token. Implementations persist only the SHA-256 digests.
     */
    CreatedSession create(UserId userId, ExternalIdentity externalId, String displayName,
            Set<GlobalRole> grantedRoles, Instant expiresAt);

    /** Looks a presented cookie token up by its SHA-256 digest. */
    Optional<SessionRecord> findByDigest(byte[] sha256Digest);

    /** Revokes the session holding this digest (idempotent). */
    void revokeByDigest(byte[] sha256Digest);
}
