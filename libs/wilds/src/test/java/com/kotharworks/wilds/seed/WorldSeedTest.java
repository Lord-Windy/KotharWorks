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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorldSeedTest {

    @Test
    void goldenStreamValuesArePinned() {
        assertEquals(2783407028160647350L, new WorldSeed(42L).stream("region", 1L, 2L).nextLong());
        assertEquals(1127986157956114328L, new WorldSeed(42L).stream("topology", 1L, 2L).nextLong());
        assertEquals(-8830264057291104620L, new WorldSeed(42L).stream("prose", 1L, 2L).nextLong());
        assertEquals(2627039839922677832L, new WorldSeed(7L).stream("region", 5L, 0L).nextLong());
        assertEquals(-8646013877010162674L, new WorldSeed(0L).stream("region", 0L, 0L).nextLong());
        assertEquals(7047607545586884408L, new WorldSeed(42L).stream("region", 3L, -7L).nextLong());
        assertEquals(-5927253998736498802L, new WorldSeed(42L).stream("biome:é-αβ", 1L, 2L).nextLong());
        assertThrows(NullPointerException.class, () -> new WorldSeed(42L).stream(null, 0L, 0L));
    }

    @Test
    void goldenSequenceIsPinned() {
        SeededRng rng = new WorldSeed(42L).stream("edge", -2_147_483_648L, 2_147_483_647L);
        assertEquals(6927718585549983871L, rng.nextLong());
        assertEquals(3322887773325065831L, rng.nextLong());
        assertEquals(-3052716529090688458L, rng.nextLong());
    }

    @Test
    void domainsAreIsolated() {
        assertNotEquals(new WorldSeed(42L).stream("topology", 1L, 2L).nextLong(),
                        new WorldSeed(42L).stream("prose", 1L, 2L).nextLong());
        assertNotEquals(new WorldSeed(42L).stream("region", 1L, 2L).nextLong(),
                        new WorldSeed(42L).stream("topology", 1L, 2L).nextLong());
    }

    @Test
    void streamsAreIndependentOfCreationOrder() {
        long[] coordinates = {3L, -7L, 100L, 200L, 0L, 9L};
        long[] forward = new long[coordinates.length];
        long[] reverse = new long[coordinates.length];
        for (int i = 0; i < coordinates.length; i++) {
            forward[i] = new WorldSeed(42L).stream("region", coordinates[i], 77L).nextLong();
        }
        for (int i = coordinates.length - 1; i >= 0; i--) {
            reverse[i] = new WorldSeed(42L).stream("region", coordinates[i], 77L).nextLong();
        }
        assertArrayEquals(forward, reverse);

        SeededRng first = new WorldSeed(42L).stream("region", 3L, -7L);
        new WorldSeed(42L).stream("region", 100L, 200L).nextLong();
        new WorldSeed(42L).stream("other", 0L, 0L).nextLong();
        SeededRng second = new WorldSeed(42L).stream("region", 3L, -7L);
        assertEquals(first.nextLong(), second.nextLong());
        assertEquals(first.nextLong(), second.nextLong());
    }

    @Test
    void regionHashIsPinnedAndDelegatesToRegionDomain() {
        WorldSeed seed = new WorldSeed(42L);
        assertEquals(7047607545586884408L, seed.regionHash(3, -7));
        assertEquals(-4935739986633482719L, seed.regionHash(100, 200));
        assertEquals(6968173194758821256L, new WorldSeed(1001L).regionHash(0, 0));
        assertEquals(seed.stream("region", 0L, 0L).nextLong(), seed.regionHash(0, 0));
        assertEquals(seed.stream("region", 3L, -7L).nextLong(), seed.regionHash(3, -7));
    }
}
