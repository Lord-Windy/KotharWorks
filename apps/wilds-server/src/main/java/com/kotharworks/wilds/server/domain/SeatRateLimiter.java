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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import com.kotharworks.wilds.server.ports.ClockPort;

/**
 * Per-seat token-bucket rate limiter for authorized API traffic. Pure 64-bit
 * integer math in nanotokens ({@code 1 token = 1_000_000_000} nanotokens) over
 * an injected {@link ClockPort}: every elapsed nanosecond adds exact
 * {@code elapsedNanos * ratePerSec} nanotokens with zero division or
 * sub-millisecond truncation loss, and buckets initialize full at construction
 * time regardless of whether {@code System.nanoTime()} is negative or positive.
 */
public final class SeatRateLimiter {

    private static final long NANO_PER_TOKEN = 1_000_000_000L;
    private static final long MAX_EFFECTIVE_CAP = 1_000_000_000L;

    private final long ratePerSec;
    private final long burst;
    private final long effectiveRate;
    private final long maxNanoTokens;
    private final ClockPort clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        private final ReentrantLock lock = new ReentrantLock();
        private long nanoTokens;
        private long lastRefillNanos;

        Bucket(long initialNanoTokens, long nowNanos) {
            this.nanoTokens = initialNanoTokens;
            this.lastRefillNanos = nowNanos;
        }
    }

    /** Builds a limiter over the production monotonic clock. */
    public SeatRateLimiter(long ratePerSec, long burst) {
        this(ratePerSec, burst, ClockPort.SYSTEM);
    }

    /** Builds a limiter admitting {@code ratePerSec} requests/s with burst headroom. */
    public SeatRateLimiter(long ratePerSec, long burst, ClockPort clock) {
        if (ratePerSec < 1) {
            throw new IllegalArgumentException("ratePerSec must be >= 1: " + ratePerSec);
        }
        if (burst < 1) {
            throw new IllegalArgumentException("burst must be >= 1: " + burst);
        }
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ratePerSec = ratePerSec;
        this.burst = burst;
        this.effectiveRate = Math.min(ratePerSec, MAX_EFFECTIVE_CAP);
        this.maxNanoTokens = Math.min(burst, MAX_EFFECTIVE_CAP) * NANO_PER_TOKEN;
    }

    /**
     * Consumes one token for {@code seatId} if the bucket allows.
     *
     * @return {@code false} when the bucket is empty (caller renders
     *         {@code 429 RATE_LIMITED})
     */
    public boolean tryAcquire(long seatId) {
        long nowNanos = clock.nanoTime();
        Bucket bucket = buckets.computeIfAbsent(seatId, _ -> new Bucket(maxNanoTokens, nowNanos));
        bucket.lock.lock();
        try {
            long elapsedNanos = nowNanos - bucket.lastRefillNanos;
            if (elapsedNanos > 0) {
                long clampedElapsed = Math.min(elapsedNanos, Long.MAX_VALUE / effectiveRate);
                long added = clampedElapsed * effectiveRate;
                long headroom = maxNanoTokens - bucket.nanoTokens;
                bucket.nanoTokens = added >= headroom ? maxNanoTokens : bucket.nanoTokens + added;
                bucket.lastRefillNanos = nowNanos;
            }
            if (bucket.nanoTokens < NANO_PER_TOKEN) {
                return false;
            }
            bucket.nanoTokens -= NANO_PER_TOKEN;
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
