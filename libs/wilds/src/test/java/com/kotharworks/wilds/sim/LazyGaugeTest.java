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
package com.kotharworks.wilds.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LazyGaugeTest {

    private static final long ONE_YEAR_MS = 31_536_000_000L;

    @Test
    void subSecondBoundariesAreExact() {
        LazyGauge gauge = new LazyGauge(50_000L, 1_000L, -1_000L, 0L, 100_000L);
        assertEquals(49_001L, gauge.valueMilliAt(1_999L), "999ms elapsed must yield delta -999");
        assertEquals(49_000L, gauge.valueMilliAt(2_000L), "1000ms elapsed must yield delta -1000");
    }

    @Test
    void divisionTruncatesTowardZero() {
        LazyGauge gauge = new LazyGauge(10_000L, 0L, -1L, 0L, 100_000L);
        assertEquals(9_999L, gauge.valueMilliAt(1_499L), "-1499/1000 truncates to -1, not -2");
        assertEquals(9_999L, gauge.valueMilliAt(1_500L), "-1500/1000 truncates to -1, not -2");
        assertEquals(9_998L, gauge.valueMilliAt(2_000L));
    }

    @Test
    void anchorReturnedAtAndBeforeAnchorTimeAndAtZeroRate() {
        LazyGauge gauge = new LazyGauge(50_000L, 1_000L, -1_000L, 0L, 100_000L);
        assertEquals(50_000L, gauge.valueMilliAt(1_000L), "nowMs == anchorTimeMs returns anchor");
        assertEquals(50_000L, gauge.valueMilliAt(500L), "nowMs < anchorTimeMs returns anchor");
        LazyGauge idle = new LazyGauge(50_000L, 0L, 0L, 0L, 100_000L);
        assertEquals(50_000L, idle.valueMilliAt(ONE_YEAR_MS), "zero rate returns anchor");
    }

    @Test
    void clampsToMinAndMax() {
        LazyGauge regen = new LazyGauge(0L, 0L, 1_000L, 0L, 50_000L);
        assertEquals(49_999L, regen.valueMilliAt(49_999L));
        assertEquals(50_000L, regen.valueMilliAt(1_000_000L), "regen past max clamps to maxMilli");
        LazyGauge drain = new LazyGauge(50_000L, 0L, -1_000L, 0L, 50_000L);
        assertEquals(1L, drain.valueMilliAt(49_999L));
        assertEquals(0L, drain.valueMilliAt(1_000_000L), "drain past min clamps to minMilli");
    }

    @Test
    void threeHundredSixtyFiveDayIdleJumpSaturatesWithoutOverflow() {
        LazyGauge regen = new LazyGauge(0L, 0L, 1_000_000_000L, 0L, 100_000L);
        assertEquals(100_000L, regen.valueMilliAt(ONE_YEAR_MS),
                "elapsed*rate ~3.15e19 overflows long; must saturate to maxMilli");
        LazyGauge drain = new LazyGauge(100_000L, 0L, -1_000_000_000L, 0L, 100_000L);
        assertEquals(0L, drain.valueMilliAt(ONE_YEAR_MS),
                "negative overflow must saturate to minMilli");
    }

    @Test
    void constructorValidatesBounds() {
        assertThrows(IllegalArgumentException.class, () -> new LazyGauge(0L, 0L, 0L, 10L, 5L));
        assertThrows(IllegalArgumentException.class, () -> new LazyGauge(11L, 0L, 0L, 0L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new LazyGauge(-1L, 0L, 0L, 0L, 10L));
    }

    @Test
    void reanchorMaterializesThenAddsLump() {
        LazyGauge gauge = new LazyGauge(50_000L, 0L, -1_000L, 0L, 100_000L);
        LazyGauge anchored = gauge.reanchor(2_000L, -5_000L, 0L);
        assertEquals(43_000L, anchored.anchorMilli(), "50_000 - 2_000ms drain - 5_000 lump");
        assertEquals(2_000L, anchored.anchorTimeMs());
        assertEquals(0L, anchored.rateMilliPerSec());
        assertEquals(43_000L, anchored.valueMilliAt(9_999_999L), "zero rate freezes the lump");
    }

    @Test
    void reanchorLumpClampsToBounds() {
        LazyGauge high = new LazyGauge(99_000L, 0L, 0L, 0L, 100_000L);
        LazyGauge bumpedUp = high.reanchor(10L, 50_000L, 5L);
        assertEquals(100_000L, bumpedUp.anchorMilli());
        assertEquals(100_000L, bumpedUp.maxMilli());
        LazyGauge low = new LazyGauge(1_000L, 0L, 0L, 0L, 100_000L);
        LazyGauge bumpedDown = low.reanchor(10L, -50_000L, 5L);
        assertEquals(0L, bumpedDown.anchorMilli());
        assertEquals(5L, bumpedDown.rateMilliPerSec());
    }

    @Test
    void reanchorNeverMovesAnchorTimeBackward() {
        LazyGauge gauge = new LazyGauge(50_000L, 1_000L, -1_000L, 0L, 100_000L);
        assertEquals(50_000L, gauge.valueMilliAt(500L), "pre-state at earlier nowMs is the anchor");
        LazyGauge anchored = gauge.reanchor(500L, -2_000L, 7L);
        assertEquals(48_000L, anchored.anchorMilli(), "materializes anchor value then adds lump");
        assertEquals(1_000L, anchored.anchorTimeMs(), "anchorTimeMs must stay at the old value");
        assertEquals(7L, anchored.rateMilliPerSec());
        assertEquals(48_000L, anchored.valueMilliAt(500L));
    }
}
