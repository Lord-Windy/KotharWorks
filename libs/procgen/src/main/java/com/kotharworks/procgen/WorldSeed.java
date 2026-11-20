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
package com.kotharworks.procgen;

/**
 * Deterministic coordinate-hashed seed helper (scaffold).
 * Order-independent: {@code regionHash(x, y)} never depends on visitation order.
 */
public record WorldSeed(long value) {

    public long regionHash(int x, int y) {
        long h = value ^ (Integer.toUnsignedLong(x) * 0x9E3779B97F4A7C15L)
                ^ (Integer.toUnsignedLong(y) * 0xBF58476D1CE4E5B9L);
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }
}
