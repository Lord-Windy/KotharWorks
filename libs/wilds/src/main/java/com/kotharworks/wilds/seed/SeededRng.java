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

/**
 * Stateful SplitMix64 integer RNG scoped to a single local operation; never store
 * one inside simulation state. Integer-only API: no floats or doubles anywhere.
 */
public final class SeededRng {

    private long state;

    SeededRng(long seed) {
        this.state = seed;
    }

    public long nextLong() {
        state += 0x9E3779B97F4A7C15L;
        return WorldSeed.mix64(state);
    }

    /** Uniform value in {@code [0, bound)} via unbiased rejection sampling. */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive: " + bound);
        }
        return (int) nextBounded(bound);
    }

    /** Uniform inclusive range; span computed in {@code long} to survive full-int ranges. */
    public int nextRange(int minInclusive, int maxInclusive) {
        if (minInclusive > maxInclusive) {
            throw new IllegalArgumentException(
                    "minInclusive > maxInclusive: " + minInclusive + " > " + maxInclusive);
        }
        long span = (long) maxInclusive - (long) minInclusive + 1L;
        return (int) (minInclusive + nextBounded(span));
    }

    /** Uniform value in {@code [0, 999]}. */
    public int nextPermille() {
        return nextInt(1_000);
    }

    /** Uniform value in {@code [0, 9999]}. */
    public int nextBasisPoints() {
        return nextInt(10_000);
    }

    /** Uniform element; rejects empty lists. */
    public <T> T pick(List<T> items) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        return items.get(nextInt(items.size()));
    }

    private long nextBounded(long bound) {
        long bits;
        long value;
        do {
            bits = nextLong() >>> 1;
            value = bits % bound;
        } while (bits - value + (bound - 1) < 0L);
        return value;
    }
}
