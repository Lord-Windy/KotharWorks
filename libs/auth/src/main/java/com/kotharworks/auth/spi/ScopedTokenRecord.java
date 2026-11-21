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

import java.util.Objects;

import com.kotharworks.auth.principal.TokenId;
import com.kotharworks.auth.principal.UserId;

/**
 * Host-resolved row for one {@code iw_bot_} scoped automation token, looked
 * up by the SHA-256 digest of the presented secret. A {@code revoked} token is
 * rejected with 401; a suspended owner with 403 — so a suspended owner's
 * otherwise-valid tokens fail closed, not open.
 */
public record ScopedTokenRecord(TokenId tokenId, UserId ownerUserId, String resourceNamespace,
        long scopedResourceId, boolean revoked, boolean ownerSuspended) {

    public ScopedTokenRecord {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(resourceNamespace, "resourceNamespace");
    }
}
