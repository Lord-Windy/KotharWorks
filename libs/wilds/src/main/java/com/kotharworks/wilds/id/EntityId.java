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
 * Gameplay-neutral generational entity handle addressed by a dense slot index.
 *
 * <p>A wire id is the canonical compact string {@code "<prefix>:<index>:<generation>"} (for
 * example {@code "act:0:1"}); the generation byte rejects stale handles after slot reuse.
 * Deliberately does <em>not</em> extend {@code Comparable<EntityId>} — each concrete id type
 * implements {@code Comparable} against itself so sorted collections stay deterministic.
 */
public sealed interface EntityId permits OwnerId, ActorId, PlaceId {

    /** Dense slot index; always {@code >= 0}. */
    int index();

    /** Slot generation; always {@code >= 1}. */
    int generation();

    /** Canonical wire prefix ({@code "own"}, {@code "act"}, {@code "plc"}). */
    String prefix();

    /** Canonical compact wire form, e.g. {@code "act:0:1"}. */
    default String toWire() {
        return prefix() + ":" + index() + ":" + generation();
    }
}
