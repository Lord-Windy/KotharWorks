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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * Generates and hashes {@code iw_bot_} scoped automation API tokens in the
 * canonical form {@code iw_bot_<prefix8>_<43charBase64Url>} — an 8-char
 * base64url prefix for grep-friendly identification plus a 32-byte secret
 * encoded unpadded base64url (43 chars). Only 32-byte SHA-256 digests are ever
 * persisted; presented tokens are compared constant-time via
 * {@link MessageDigest#isEqual}.
 */
public final class ApiTokenCodec {

    /** Canonical token prefix ({@code iw_bot_}). */
    public static final String TOKEN_PREFIX = "iw_bot_";

    private static final int PREFIX_BYTES = 6;
    private static final int SECRET_BYTES = 32;
    private static final int PREFIX_CHARS = 8;
    private static final int SECRET_CHARS = 43;

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private final SecureRandom random;

    public ApiTokenCodec(SecureRandom random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /** One generated token: the full wire form plus its digest (persist this, not the token). */
    public record GeneratedToken(String token, String prefix, byte[] sha256) {
    }

    /** Generates a fresh token with a random 8-char prefix and 32-byte secret. */
    public GeneratedToken generate() {
        byte[] prefixBytes = new byte[PREFIX_BYTES];
        random.nextBytes(prefixBytes);
        byte[] secretBytes = new byte[SECRET_BYTES];
        random.nextBytes(secretBytes);
        String prefix = BASE64URL.encodeToString(prefixBytes);
        String token = TOKEN_PREFIX + prefix + "_" + BASE64URL.encodeToString(secretBytes);
        return new GeneratedToken(token, prefix, sha256(token));
    }

    /** The token's wire form: {@code iw_bot_} + 8 prefix chars + {@code _} + 43 secret chars. */
    public static boolean isApiToken(String value) {
        return value != null && value.startsWith(TOKEN_PREFIX);
    }

    /** SHA-256 digest of the token's UTF-8 bytes (what the host persists). */
    public static byte[] sha256(String token) {
        Objects.requireNonNull(token, "token");
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK is missing SHA-256", e);
        }
    }

    /**
     * Constant-time comparison of a presented token against a stored digest.
     *
     * @param storedSha256 digest previously returned by {@link #sha256(String)}
     * @return {@code true} only when the presented token hashes to the stored digest
     */
    public static boolean matchesDigest(String presentedToken, byte[] storedSha256) {
        Objects.requireNonNull(storedSha256, "storedSha256");
        if (presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presentedToken), storedSha256);
    }

    /** Expected full token length: {@code 7 + 8 + 1 + 43}. Kept for audits/tests. */
    public static int expectedTokenLength() {
        return TOKEN_PREFIX.length() + PREFIX_CHARS + 1 + SECRET_CHARS;
    }
}
