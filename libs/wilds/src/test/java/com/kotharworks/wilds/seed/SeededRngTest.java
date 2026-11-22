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
package com.kotharworks.wilds.seed;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeededRngTest {

    private static SeededRng rng(long seed, String domain) {
        return new WorldSeed(seed).stream(domain, 0L, 0L);
    }

    @Test
    void nextIntStaysWithinBoundAndDistributesReasonably() {
        SeededRng random = rng(7L, "buckets");
        int[] counts = new int[10];
        for (int i = 0; i < 10_000; i++) {
            int value = random.nextInt(10);
            assertTrue(value >= 0 && value < 10, "out of [0,10): " + value);
            counts[value]++;
        }
        for (int count : counts) {
            assertTrue(count > 500, "bucket under-filled: " + count);
        }
    }

    @Test
    void nextIntRejectsNonPositiveBounds() {
        SeededRng random = rng(1L, "bounds");
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(0));
        assertThrows(IllegalArgumentException.class, () -> random.nextInt(-3));
    }

    @Test
    void nextRangeCoversInclusiveBoundsAndSurvivesFullIntSpan() {
        SeededRng narrow = rng(9L, "narrow");
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 1_000; i++) {
            int value = narrow.nextRange(0, 3);
            assertTrue(value >= 0 && value <= 3);
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        assertEquals(0, min);
        assertEquals(3, max);

        SeededRng negativeRange = rng(9L, "negative");
        int negMin = Integer.MAX_VALUE;
        int negMax = Integer.MIN_VALUE;
        for (int i = 0; i < 1_000; i++) {
            int value = negativeRange.nextRange(-10, -7);
            assertTrue(value >= -10 && value <= -7, "out of [-10,-7]: " + value);
            negMin = Math.min(negMin, value);
            negMax = Math.max(negMax, value);
        }
        assertEquals(-10, negMin);
        assertEquals(-7, negMax);

        SeededRng singleton = rng(9L, "singleton");
        assertEquals(0, singleton.nextRange(0, 0));
        assertEquals(-42, singleton.nextRange(-42, -42));
        assertEquals(Integer.MIN_VALUE, singleton.nextRange(Integer.MIN_VALUE, Integer.MIN_VALUE));
        assertEquals(Integer.MAX_VALUE, singleton.nextRange(Integer.MAX_VALUE, Integer.MAX_VALUE));

        SeededRng full = rng(9L, "full");
        int negatives = 0;
        int positives = 0;
        for (int i = 0; i < 10_000; i++) {
            int value = full.nextRange(Integer.MIN_VALUE, Integer.MAX_VALUE);
            if (value < 0) {
                negatives++;
            } else if (value > 0) {
                positives++;
            }
        }
        assertTrue(negatives > 4_000, "expected roughly half negative draws, got " + negatives);
        assertTrue(positives > 4_000, "expected roughly half positive draws, got " + positives);

        // Non-power-of-two span > Integer.MAX_VALUE (span = 3_000_000_001L) exercises >31-bit rejection sampling.
        SeededRng wide = rng(9L, "wide");
        int low = 0;
        int highAbove31BitOffset = 0;
        for (int i = 0; i < 10_000; i++) {
            int value = wide.nextRange(-1_500_000_000, 1_500_000_000);
            assertTrue(value >= -1_500_000_000 && value <= 1_500_000_000);
            if (value < -500_000_000) {
                low++;
            } else if (value > 700_000_000) {
                highAbove31BitOffset++;
            }
        }
        assertTrue(low > 2_500, "under-sampled lower third: " + low);
        assertTrue(highAbove31BitOffset > 2_000, "under-sampled >31-bit offset region: " + highAbove31BitOffset);

        assertThrows(IllegalArgumentException.class, () -> rng(9L, "inverted").nextRange(5, -5));
    }

    @Test
    void nextPermilleStaysWithinZeroToNineHundredNinetyNine() {
        SeededRng random = rng(11L, "permille");
        for (int i = 0; i < 10_000; i++) {
            int value = random.nextPermille();
            assertTrue(value >= 0 && value <= 999, "out of [0,999]: " + value);
        }
    }

    @Test
    void nextBasisPointsStayWithinZeroToNineThousandNineHundredNinetyNine() {
        SeededRng random = rng(6L, "basis");
        for (int i = 0; i < 10_000; i++) {
            int value = random.nextBasisPoints();
            assertTrue(value >= 0 && value <= 9_999, "out of [0,9999]: " + value);
        }
    }

    @Test
    void pickIsDeterministicAndRejectsEmptyOrNullList() {
        List<String> items = List.of("alpha", "beta", "gamma", "delta");
        assertEquals(rng(5L, "pick").pick(items), rng(5L, "pick").pick(items));
        assertEquals("delta", rng(5L, "pick").pick(items));
        assertThrows(IllegalArgumentException.class, () -> rng(5L, "pickEmpty").pick(List.of()));
        assertThrows(NullPointerException.class, () -> rng(5L, "pickNull").pick(null));
    }
}
