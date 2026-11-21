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

import java.util.Arrays;
import java.util.Objects;

/**
 * A freshly minted scoped API token as the store persisted it: the raw
 * {@code iw_bot_...} secret (shown to the caller exactly once), its permanent
 * id (used for later revocation), the 8-char display prefix, and the SHA-256
 * digest that is the only long-lived credential form.
 *
 * @param rawToken full wire form {@code iw_bot_<prefix>_<secret>}
 * @param tokenId  permanent token id (revocation key)
 * @param prefix   display prefix (audit surfaces only ever see this)
 * @param sha256   digest persisted in place of the secret
 */
public record CreatedToken(String rawToken, long tokenId, String prefix, byte[] sha256) {

    public CreatedToken {
        Objects.requireNonNull(rawToken, "rawToken");
        Objects.requireNonNull(prefix, "prefix");
        sha256 = sha256.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CreatedToken other
                && tokenId == other.tokenId
                && rawToken.equals(other.rawToken)
                && prefix.equals(other.prefix)
                && Arrays.equals(sha256, other.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawToken, tokenId, prefix);
    }

    @Override
    public String toString() {
        return "CreatedToken[tokenId=" + tokenId + ", prefix=" + prefix + "]";
    }
}
