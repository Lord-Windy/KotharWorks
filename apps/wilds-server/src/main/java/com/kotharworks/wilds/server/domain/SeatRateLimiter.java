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
package com.kotharworks.wilds.server.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-seat token-bucket rate limiter for authorized API traffic. Pure integer
 * math on {@code System.nanoTime()} with milli-token granularity: one token is
 * 1000 milli-tokens; buckets refill at {@code ratePerSec} tokens/s up to
 * {@code burst} and live in a {@link ConcurrentHashMap} keyed by seat id.
 */
public final class SeatRateLimiter {

    private static final long MILLI_PER_TOKEN = 1_000L;
    private static final long NANOS_PER_MILLI_TOKEN = 1_000_000L;

    private final long ratePerSec;
    private final long burst;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        private final ReentrantLock lock = new ReentrantLock();
        private long milliTokens;
        private long lastRefillNanos;
    }

    /** Builds a limiter admitting {@code ratePerSec} requests/s with burst headroom. */
    public SeatRateLimiter(long ratePerSec, long burst) {
        if (ratePerSec < 1) {
            throw new IllegalArgumentException("ratePerSec must be >= 1: " + ratePerSec);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1: " + burst);
        }
        this.ratePerSec = ratePerSec;
        this.burst = burst;
    }

    /**
     * Consumes one token for {@code seatId} if the bucket allows.
     *
     * @return {@code false} when the bucket is empty (caller renders
     *         {@code 429 RATE_LIMITED})
     */
    public boolean tryAcquire(long seatId) {
        long nowNanos = System.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(seatId, _ -> new Bucket());
        bucket.lock.lock();
        try {
            long elapsedNanos = nowNanos - bucket.lastRefillNanos;
            if (elapsedNanos > 0) {
                // Clamp huge sleeps so elapsed * rate cannot overflow.
                long clamped = Math.min(elapsedNanos, Long.MAX_VALUE / (ratePerSec + 1));
                bucket.milliTokens = Math.min(burst * MILLI_PER_TOKEN,
                        bucket.milliTokens + clamped * ratePerSec / NANOS_PER_MILLI_TOKEN);
                bucket.lastRefillNanos = nowNanos;
            }
            if (bucket.milliTokens < MILLI_PER_TOKEN) {
                return false;
            }
            bucket.milliTokens -= MILLI_PER_TOKEN;
            return true;
        } finally {
            bucket.lock.unlock();
        }
    }

    /** Configured sustained rate (requests per second). */
    public long ratePerSec() {
        return ratePerSec;
    }

    /** Configured burst headroom (tokens). */
    public long burst() {
        return burst;
    }
}
