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
package com.kotharworks.wilds.replay;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link WalHeader}: optional {@code rulesFingerprint} encoding and legacy compatibility. */
class WalHeaderTest {

    /** A header line exactly as written before {@code rulesFingerprint} existed. */
    private static final String LEGACY_LINE =
            "{\"type\":\"WAL_HEADER\",\"worldSeed\":4805198,\"rulesVersion\":1,\"engineVersion\":1}";

    @Test
    void roundTripsWithFingerprintIncludingExtremes() {
        for (long fingerprint : new long[] {0L, 1L, -1L, 0x26679a32edcffaafL,
                -6107795532212224383L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            WalHeader header = new WalHeader(-7L, 3, 2, fingerprint);
            WalHeader decoded = WalHeader.decode(header.encode());
            assertEquals(header, decoded);
            assertEquals(fingerprint, decoded.rulesFingerprint());
        }
    }

    @Test
    void encodesFingerprintAsTrailingSignedIntegerKey() {
        assertEquals("{\"type\":\"WAL_HEADER\",\"worldSeed\":4805198,\"rulesVersion\":1,"
                        + "\"engineVersion\":1,\"rulesFingerprint\":-9223372036854775808}",
                new WalHeader(4805198L, 1, 1, Long.MIN_VALUE).encode());
        assertEquals("{\"type\":\"WAL_HEADER\",\"worldSeed\":4805198,\"rulesVersion\":1,"
                        + "\"engineVersion\":1,\"rulesFingerprint\":2767350039571331759}",
                new WalHeader(4805198L, 1, 1, 0x26679a32edcffaafL).encode());
    }

    @Test
    void roundTripsWithoutFingerprint() {
        WalHeader header = new WalHeader(Long.MIN_VALUE, 1, 1);
        assertNull(header.rulesFingerprint());
        assertEquals(header, new WalHeader(Long.MIN_VALUE, 1, 1, null));
        WalHeader decoded = WalHeader.decode(header.encode());
        assertEquals(header, decoded);
        assertNull(decoded.rulesFingerprint());
    }

    @Test
    void encodeWithoutFingerprintIsByteIdenticalToLegacyForm() {
        assertEquals(LEGACY_LINE, new WalHeader(4805198L, 1, 1).encode());
        assertEquals(LEGACY_LINE, new WalHeader(4805198L, 1, 1, null).encode());
    }

    @Test
    void legacyHeaderWithoutFieldDecodesToNullFingerprint() {
        WalHeader decoded = WalHeader.decode(LEGACY_LINE);
        assertEquals(new WalHeader(4805198L, 1, 1), decoded);
        assertNull(decoded.rulesFingerprint());
        // Legacy headers with whitespace / reordered keys also still decode.
        assertEquals(new WalHeader(5L, 1, 1), WalHeader.decode(
                "{ \"engineVersion\":1, \"rulesVersion\":1, \"worldSeed\":5, \"type\":\"WAL_HEADER\" }"));
    }

    @Test
    void wrongTypedOrNullFingerprintIsRejected() {
        for (String value : List.of("null", "\"0x26679a32edcffaaf\"", "\"123\"", "true",
                "[1]", "{}")) {
            String line = "{\"type\":\"WAL_HEADER\",\"worldSeed\":1,\"rulesVersion\":1,"
                    + "\"engineVersion\":1,\"rulesFingerprint\":" + value + "}";
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> WalHeader.decode(line), () -> "accepted rulesFingerprint=" + value);
            assertTrue(thrown.getMessage().contains("rulesFingerprint"), thrown.getMessage());
        }
        // Out of long range is rejected by the JSON parser itself.
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode(
                "{\"type\":\"WAL_HEADER\",\"worldSeed\":1,\"rulesVersion\":1,"
                        + "\"engineVersion\":1,\"rulesFingerprint\":9223372036854775808}"));
    }

    @Test
    void toStringShowsHexFingerprintOnlyWhenPresent() {
        assertEquals("WAL_HEADER(worldSeed=5, rulesVersion=1, engineVersion=1)",
                new WalHeader(5L, 1, 1).toString());
        assertEquals("WAL_HEADER(worldSeed=5, rulesVersion=1, engineVersion=1, "
                        + "rulesFingerprint=0x26679a32edcffaaf)",
                new WalHeader(5L, 1, 1, 0x26679a32edcffaafL).toString());
        assertEquals("WAL_HEADER(worldSeed=5, rulesVersion=1, engineVersion=1, "
                        + "rulesFingerprint=0xffffffffffffffff)",
                new WalHeader(5L, 1, 1, -1L).toString());
        assertEquals("WAL_HEADER(worldSeed=5, rulesVersion=1, engineVersion=1, "
                        + "rulesFingerprint=0x0000000000000001)",
                new WalHeader(5L, 1, 1, 1L).toString());
    }

    @Test
    void existingFieldValidationIsUnchanged() {
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode(
                "{\"type\":\"WAL_HEADER\",\"rulesVersion\":1,\"engineVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode(
                "{\"type\":\"WAL_HEADER\",\"worldSeed\":1,\"rulesVersion\":4294967296,"
                        + "\"engineVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode(
                "{\"type\":\"NOT_A_HEADER\",\"worldSeed\":1,\"rulesVersion\":1,\"engineVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> WalHeader.decode("[1]"));
    }
}
