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
package com.kotharworks.wilds.server.ports;

import java.util.Objects;

/**
 * Result of {@link WorldStorePort#ensureActiveWorld(long, int, int, int, long, OnVersionMismatch)}.
 *
 * @param world    the active world the server now simulates (never null, status
 *                 {@link WorldRecord#ACTIVE})
 * @param archived the world this call archived because it mismatched the running binary
 *                 (status {@link WorldRecord#ARCHIVED}), or {@code null} when no rollover
 *                 happened
 */
public record ActiveWorld(WorldRecord world, WorldRecord archived) {

    public ActiveWorld {
        Objects.requireNonNull(world, "world");
        if (!WorldRecord.ACTIVE.equals(world.status())) {
            throw new IllegalArgumentException("world must be ACTIVE: " + world);
        }
        if (archived != null && !WorldRecord.ARCHIVED.equals(archived.status())) {
            throw new IllegalArgumentException("archived must be ARCHIVED: " + archived);
        }
    }

    /** Whether this call rolled a mismatched world over to a fresh one. */
    public boolean rolledOver() {
        return archived != null;
    }
}
