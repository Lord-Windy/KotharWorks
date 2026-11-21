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
package com.kotharworks.wilds;

import com.kotharworks.wilds.seed.WorldSeed;

import java.util.Objects;

/**
 * Hello-world simulation engine scaffold for Ironwood Wilds.
 * Pure Java 25: zero I/O, zero wall-clock calls, deterministic integer time.
 */
public final class WildsEngine {

    public static final int API_VERSION = 1;

    public sealed interface Command {
        record Ping(long logicalTimeMs) implements Command {}
    }

    private final WorldSeed seed;
    private long nowMs;

    public WildsEngine(WorldSeed seed) {
        this.seed = Objects.requireNonNull(seed, "seed");
        this.nowMs = 0L;
    }

    public String apply(Command command) {
        Objects.requireNonNull(command, "command");
        return switch (command) {
            case Command.Ping(long logicalTimeMs) -> {
                if (logicalTimeMs > this.nowMs) {
                    this.nowMs = logicalTimeMs;
                }
                yield hello();
            }
        };
    }

    public long nowMs() {
        return nowMs;
    }

    public String hello() {
        long originSignature = Long.remainderUnsigned(seed.regionHash(0, 0), 100_000L);
        return "kotharworks/wilds scaffold (API v%d, t=%dms, origin=%05d)"
                .formatted(API_VERSION, nowMs, originSignature);
    }
}
