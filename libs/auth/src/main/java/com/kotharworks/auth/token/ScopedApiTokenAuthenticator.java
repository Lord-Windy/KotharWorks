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
package com.kotharworks.auth.token;

import java.util.Objects;

import com.kotharworks.auth.AuthErrorCode;
import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.principal.Principal;
import com.kotharworks.auth.spi.ScopedTokenLookup;
import com.kotharworks.auth.spi.ScopedTokenRecord;

/**
 * Resolves a presented {@code iw_bot_} bearer token into a
 * {@link Principal.ScopedTokenPrincipal} via the host-supplied
 * {@link ScopedTokenLookup}. Unknown tokens, digest mismatches, and revoked
 * tokens reject with 401 {@code INVALID_TOKEN}; a suspended owner rejects with
 * 403 {@code ACCOUNT_SUSPENDED} (fail-closed, even though the secret itself
 * is valid).
 */
public final class ScopedApiTokenAuthenticator {

    private final ScopedTokenLookup lookup;

    public ScopedApiTokenAuthenticator(ScopedTokenLookup lookup) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    /**
     * Authenticates a raw {@code iw_bot_...} bearer token. The token never
     * leaves digest form: it is hashed here and the SHA-256 digest is what the
     * {@link ScopedTokenLookup} resolves (so a mutated secret digest-misses and
     * is rejected exactly like an unknown token).
     *
     * @throws AuthRejectException {@link AuthErrorCode#INVALID_TOKEN} when the
     *         token is unknown or revoked; {@link AuthErrorCode#ACCOUNT_SUSPENDED}
     *         when the owning account is suspended
     */
    public Principal.ScopedTokenPrincipal authenticate(String bearerToken) {
        Objects.requireNonNull(bearerToken, "bearerToken");
        if (!ApiTokenCodec.isApiToken(bearerToken)
                || bearerToken.length() != ApiTokenCodec.expectedTokenLength()) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "malformed api token");
        }
        byte[] digest = ApiTokenCodec.sha256(bearerToken);
        ScopedTokenRecord record = lookup.findByDigest(digest)
                .orElseThrow(() -> new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "unknown api token"));
        if (record.revoked()) {
            throw new AuthRejectException(AuthErrorCode.INVALID_TOKEN, "api token revoked");
        }
        if (record.ownerSuspended()) {
            throw new AuthRejectException(AuthErrorCode.ACCOUNT_SUSPENDED, "token owner suspended");
        }
        return new Principal.ScopedTokenPrincipal(
                record.tokenId(), record.ownerUserId(), record.resourceNamespace(), record.scopedResourceId());
    }
}
