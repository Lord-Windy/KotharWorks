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
package com.kotharworks.wilds.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kotharworks.wilds.server.domain.WorldVersionMismatchException;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException.Pin;

/**
 * {@link WorldVersionMismatchException.Pin#admits} and the operator diagnostic: exactly
 * the mismatched rows are flagged, and a legacy unset stored fingerprint is never
 * compared or flagged.
 */
class WorldVersionMismatchExceptionTest {

    private static final long FP = 0x26679a32edcffaafL;

    @Test
    void admitsComparesSeedVersionsAndOnlyANonNullStoredFingerprint() {
        Pin running = new Pin(1L, 1, 1, 1, FP);
        assertTrue(new Pin(1L, 1, 1, 1, FP).admits(running));
        assertTrue(new Pin(1L, 1, 1, 1, null).admits(running), "legacy NULL matches on versions");
        assertFalse(new Pin(1L, 1, 1, 1, FP + 1).admits(running), "present fingerprint compared");
        for (Pin stored : List.of(new Pin(2L, 1, 1, 1, FP), new Pin(1L, 2, 1, 1, FP),
                new Pin(1L, 1, 2, 1, FP), new Pin(1L, 1, 1, 2, FP), new Pin(2L, 1, 1, 1, null))) {
            assertFalse(stored.admits(running), stored.toString());
        }
    }

    @Test
    void diagnosticFlagsExactlyTheDifferingRows() {
        String seedOnly = new WorldVersionMismatchException(4, new Pin(1L, 1, 1, 1, FP),
                new Pin(2L, 1, 1, 1, FP)).diagnostic();
        assertEquals(List.of("worldSeed"), flaggedRows(seedOnly), seedOnly);
        assertTrue(seedOnly.contains("active world 4 does not match this binary and --seed"),
                seedOnly);
        assertTrue(seedOnly.contains("--on-version-mismatch=archive to archive world 4"),
                seedOnly);

        String rulesAndFingerprint = new WorldVersionMismatchException(4,
                new Pin(1L, 2, 1, 1, FP + 1), new Pin(1L, 1, 1, 1, FP)).diagnostic();
        assertEquals(List.of("rulesVersion", "rulesFingerprint"),
                flaggedRows(rulesAndFingerprint), rulesAndFingerprint);
    }

    @Test
    void legacyUnsetStoredFingerprintIsShownButNeverFlagged() {
        String legacy = new WorldVersionMismatchException(1, new Pin(1L, 2, 1, 1, null),
                new Pin(1L, 1, 1, 1, FP)).diagnostic();
        assertEquals(List.of("rulesVersion"), flaggedRows(legacy), legacy);
        assertTrue(legacy.contains("unset"), legacy);
        assertTrue(legacy.contains("0x26679a32edcffaaf"), legacy);
    }

    private static List<String> flaggedRows(String diagnostic) {
        return diagnostic.lines()
                .filter(line -> line.endsWith("<- differs"))
                .map(line -> line.strip().split("\\s+")[0])
                .toList();
    }
}
