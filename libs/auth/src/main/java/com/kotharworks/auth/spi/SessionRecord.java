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
import java.util.Objects;
import java.util.Set;

import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.GlobalRole;
import com.kotharworks.auth.principal.UserId;

/**
 * A stored BFF session: only the SHA-256 digest of the raw cookie token is
 * persisted, never the token itself. {@code csrfToken} is the per-session
 * value the {@code CsrfGuard} matches against {@code X-CSRF-Token} or the
 * {@code csrf_token} form field.
 */
public record SessionRecord(byte[] sessionSha256, UserId userId, ExternalIdentity externalId,
        String displayName, Set<GlobalRole> grantedRoles, String csrfToken, Instant expiresAt) {

    public SessionRecord {
        Objects.requireNonNull(sessionSha256, "sessionSha256");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(displayName, "displayName");
        grantedRoles = Set.copyOf(grantedRoles);
        Objects.requireNonNull(csrfToken, "csrfToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
