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
package com.kotharworks.wilds.rules;

import com.kotharworks.wilds.replay.JsonCodec;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 64-bit digest of a {@link RuleRegistry} catalog: the first 8 bytes (big-endian) of
 * SHA-256 over the UTF-8 bytes of {@code JsonCodec.write(RulesJson.catalog(rules))}.
 * {@code rulesVersion} and {@code rulesFingerprint} are not part of the input.
 *
 * <p>Deliberately <em>not</em> folded into {@code WorldHash}: it describes the binary's
 * rules, not world state. It is recorded alongside worlds (WAL header, world metadata) so
 * a binary whose rules differ is detected even at an unchanged rules version, and pinned
 * by {@code RulesVersionPinTest} so a rules change forces a version bump.
 *
 * <p>Display form ({@link #toHex}) is {@code 0x} plus 16 lowercase hex digits, shared by
 * headless, server and tests.
 */
public final class RulesFingerprint {

    private static final String HEX_PREFIX = "0x";
    private static final int HEX_DIGITS = 16;

    private RulesFingerprint() {
    }

    /** Fingerprint of {@code rules}' canonical catalog; deterministic across runs. */
    public static long of(RuleRegistry rules) {
        Objects.requireNonNull(rules, "rules");
        byte[] input = JsonCodec.write(RulesJson.catalog(rules)).getBytes(StandardCharsets.UTF_8);
        byte[] digest = newDigest().digest(input);
        long value = 0L;
        for (int i = 0; i < Long.BYTES; i++) {
            value = (value << 8) | (digest[i] & 0xFFL);
        }
        return value;
    }

    /** {@code 0x} + 16 lowercase, zero-padded hex digits (two's complement for negatives). */
    public static String toHex(long fingerprint) {
        return HEX_PREFIX + HexFormat.of().toHexDigits(fingerprint);
    }

    /**
     * Strict inverse of {@link #toHex}: exactly {@code 0x} followed by 16 lowercase hex
     * digits, otherwise {@link IllegalArgumentException}.
     */
    public static long parseHex(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() != HEX_PREFIX.length() + HEX_DIGITS || !text.startsWith(HEX_PREFIX)) {
            throw new IllegalArgumentException(
                    "rules fingerprint must be 0x followed by 16 lowercase hex digits: \""
                            + text + "\"");
        }
        long value = 0L;
        for (int i = HEX_PREFIX.length(); i < text.length(); i++) {
            char c = text.charAt(i);
            int digit;
            if (c >= '0' && c <= '9') {
                digit = c - '0';
            } else if (c >= 'a' && c <= 'f') {
                digit = c - 'a' + 10;
            } else {
                throw new IllegalArgumentException(
                        "rules fingerprint must be 0x followed by 16 lowercase hex digits: \""
                                + text + "\"");
            }
            value = (value << 4) | digit;
        }
        return value;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 MessageDigest unavailable", missing);
        }
    }
}
