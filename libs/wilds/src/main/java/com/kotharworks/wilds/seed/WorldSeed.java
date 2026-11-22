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

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Deterministic world seed providing domain-separated, order-independent RNG streams.
 * Domain salts use explicit FNV-1a over UTF-8 bytes (never {@code String.hashCode})
 * so streams are stable across languages and tools.
 */
public record WorldSeed(long value) {

    /** Isolated SplitMix64 stream for a named domain and two coordinates. */
    public SeededRng stream(String domain, long a, long b) {
        Objects.requireNonNull(domain, "domain");
        long domainSalt = mix64(fnv1a64(domain));
        long seed = mix64(value ^ domainSalt)
                ^ mix64(a * 0xBF58476D1CE4E5B9L)
                ^ mix64(b * 0x94D049BB133111EBL);
        return new SeededRng(mix64(seed));
    }

    /** Order-independent coordinate hash backed by the {@code "region"} domain stream. */
    public long regionHash(int x, int y) {
        return stream("region", x, y).nextLong();
    }

    private static long fnv1a64(String domain) {
        long hash = 0xCBF29CE484222325L;
        for (byte b : domain.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xFFL;
            hash *= 0x100000001B3L;
        }
        return hash;
    }

    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
