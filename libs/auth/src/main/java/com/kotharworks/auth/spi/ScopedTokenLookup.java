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

import java.util.Optional;

/**
 * Host SPI: resolves a presented {@code iw_bot_} token digest to its
 * {@link ScopedTokenRecord} (in-memory or Postgres). Empty means unknown token.
 */
@FunctionalInterface
public interface ScopedTokenLookup {

    /** Looks a presented token up by its SHA-256 digest. */
    Optional<ScopedTokenRecord> findByDigest(byte[] sha256Digest);
}
