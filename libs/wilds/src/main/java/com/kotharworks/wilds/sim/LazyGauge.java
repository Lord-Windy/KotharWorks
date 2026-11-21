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

import java.math.BigInteger;

/**
 * 100% integer lazy gauge drifting linearly at a fixed milli-per-second rate from its
 * anchor, clamped to {@code [minMilli, maxMilli]} — zero-tick continuous interpolation
 * with no floating-point drift.
 *
 * <p>All math is exact integer arithmetic; multi-month idle jumps saturate cleanly via a
 * {@link BigInteger} fallback instead of overflowing or throwing. Integer division by
 * 1000 truncates toward zero (Java semantics).
 */
public record LazyGauge(
        long anchorMilli,
        long anchorTimeMs,
        long rateMilliPerSec,
        long minMilli,
        long maxMilli) {

    public LazyGauge {
        if (minMilli > maxMilli) {
            throw new IllegalArgumentException("minMilli must be <= maxMilli: "
                    + minMilli + " > " + maxMilli);
        }
        if (anchorMilli < minMilli || anchorMilli > maxMilli) {
            throw new IllegalArgumentException("anchorMilli must be within [minMilli, maxMilli]: "
                    + anchorMilli + " not in [" + minMilli + ", " + maxMilli + "]");
        }
    }

    /**
     * Value at {@code nowMs}: the anchor when {@code nowMs <= anchorTimeMs} or the rate is
     * zero; otherwise {@code anchorMilli + (elapsedMs * rateMilliPerSec) / 1000} clamped to
     * {@code [minMilli, maxMilli]}, saturating on overflow.
     */
    public long valueMilliAt(long nowMs) {
        if (nowMs <= anchorTimeMs || rateMilliPerSec == 0L) {
            return anchorMilli;
        }
        try {
            long elapsedMs = Math.subtractExact(nowMs, anchorTimeMs);
            long deltaMilli = Math.multiplyExact(elapsedMs, rateMilliPerSec) / 1_000L;
            return Math.clamp(Math.addExact(anchorMilli, deltaMilli), minMilli, maxMilli);
        } catch (ArithmeticException overflow) {
            return saturatedValueMilli(nowMs);
        }
    }

    /**
     * Materializes the current value at {@code nowMs}, adds {@code lumpDeltaMilli} (clamped
     * to {@code [minMilli, maxMilli]}), and re-anchors at {@code max(nowMs, anchorTimeMs)} —
     * the anchor time never moves backward.
     */
    public LazyGauge reanchor(long nowMs, long lumpDeltaMilli, long newRateMilliPerSec) {
        long currentMilli = valueMilliAt(nowMs);
        long anchoredMilli = clampedAdd(currentMilli, lumpDeltaMilli);
        return new LazyGauge(
                anchoredMilli,
                Math.max(nowMs, anchorTimeMs),
                newRateMilliPerSec,
                minMilli,
                maxMilli);
    }

    /** Exact saturated value for elapsed spans whose plain-long math would overflow. */
    private long saturatedValueMilli(long nowMs) {
        BigInteger value = BigInteger.valueOf(nowMs)
                .subtract(BigInteger.valueOf(anchorTimeMs))
                .multiply(BigInteger.valueOf(rateMilliPerSec))
                .divide(BigInteger.valueOf(1_000L))
                .add(BigInteger.valueOf(anchorMilli));
        if (value.compareTo(BigInteger.valueOf(maxMilli)) > 0) {
            return maxMilli;
        }
        if (value.compareTo(BigInteger.valueOf(minMilli)) < 0) {
            return minMilli;
        }
        return value.longValue();
    }

    private long clampedAdd(long base, long delta) {
        try {
            return Math.clamp(Math.addExact(base, delta), minMilli, maxMilli);
        } catch (ArithmeticException overflow) {
            BigInteger value = BigInteger.valueOf(base).add(BigInteger.valueOf(delta));
            if (value.compareTo(BigInteger.valueOf(maxMilli)) > 0) {
                return maxMilli;
            }
            return minMilli;
        }
    }
}
