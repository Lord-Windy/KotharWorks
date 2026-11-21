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
package com.kotharworks.wilds.id;

/**
 * Generational handle to an owning entity, ordered by {@code (index, generation)}.
 */
public record OwnerId(int index, int generation) implements EntityId, Comparable<OwnerId> {

    public OwnerId {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be >= 1: " + generation);
        }
    }

    @Override
    public String prefix() {
        return "own";
    }

    @Override
    public int compareTo(OwnerId other) {
        int c = Integer.compare(index, other.index);
        return c != 0 ? c : Integer.compare(generation, other.generation);
    }

    /** Parses the canonical wire form, e.g. {@code "own:0:1"}. */
    public static OwnerId parse(String wire) {
        int[] ig = EntityIds.parseIndexGeneration(wire, "own");
        return new OwnerId(ig[0], ig[1]);
    }
}
