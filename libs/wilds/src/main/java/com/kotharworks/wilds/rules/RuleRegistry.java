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
package com.kotharworks.wilds.rules;

import com.kotharworks.wilds.id.RuleId;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Compile-time structural rule catalog: the closed set of place kinds and task kinds a
 * world runs under, keyed by {@link RuleId} and iterated in deterministic lexicographic
 * order ({@code TreeMap}, never hash-ordered). Immutable after construction.
 *
 * <p>Deliberately gameplay-neutral: no doctrines, skills, or combat tables — only the
 * structural vocabulary the neutral {@code WorldState} schema needs.
 */
public final class RuleRegistry {

    /** Rules schema version carried by every {@code WorldState}; bump on schema change. */
    public static final int WILDS_RULES_VERSION = 1;

    /** Structural kind of a place (e.g. {@code camp}, {@code trail}, {@code landmark}). */
    public record PlaceKindDef(RuleId id, String name) {
        public PlaceKindDef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }
    }

    /**
     * Structural kind of a task with its duration/batch bounds.
     *
     * <p>For {@code travel} the duration is edge-driven ({@code PlaceEdge.travelDurationMs}),
     * so its {@code minDurationMs}/{@code maxDurationMs} are unused placeholder bounds
     * ({@code 0 .. Long.MAX_VALUE}); only {@code maxBatches = 1} is meaningful.
     */
    public record TaskKindDef(RuleId id, String name, long minDurationMs, long maxDurationMs,
                              int maxBatches) {
        public TaskKindDef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("name must not be blank");
            }
            if (minDurationMs < 0) {
                throw new IllegalArgumentException("minDurationMs must be >= 0: " + minDurationMs);
            }
            if (maxDurationMs < minDurationMs) {
                throw new IllegalArgumentException("maxDurationMs must be >= minDurationMs: "
                        + maxDurationMs + " < " + minDurationMs);
            }
            if (maxBatches < 1) {
                throw new IllegalArgumentException("maxBatches must be >= 1: " + maxBatches);
            }
        }
    }

    private final TreeMap<RuleId, PlaceKindDef> placeKinds;
    private final TreeMap<RuleId, TaskKindDef> taskKinds;

    public RuleRegistry(Map<RuleId, PlaceKindDef> placeKinds, Map<RuleId, TaskKindDef> taskKinds) {
        Objects.requireNonNull(placeKinds, "placeKinds");
        Objects.requireNonNull(taskKinds, "taskKinds");
        this.placeKinds = copyValidated(placeKinds, PlaceKindDef::id, "placeKinds");
        this.taskKinds = copyValidated(taskKinds, TaskKindDef::id, "taskKinds");
    }

    private static <D> TreeMap<RuleId, D> copyValidated(
            Map<RuleId, D> source, Function<D, RuleId> idExtractor, String mapName) {
        TreeMap<RuleId, D> copy = new TreeMap<>();
        for (Map.Entry<RuleId, D> entry : source.entrySet()) {
            RuleId key = Objects.requireNonNull(entry.getKey(), mapName + " contains a null key");
            D def = Objects.requireNonNull(entry.getValue(), mapName + " contains a null value");
            RuleId defId = idExtractor.apply(def);
            if (!key.equals(defId)) {
                throw new IllegalArgumentException(mapName + " key " + key.slug()
                        + " does not match definition id " + defId.slug());
            }
            copy.put(key, def);
        }
        return copy;
    }

    /**
     * The stock structural catalog: place kinds {@code camp}/{@code trail}/{@code landmark}
     * and task kinds {@code wait}/{@code travel}.
     */
    public static RuleRegistry minimalDefault() {
        TreeMap<RuleId, PlaceKindDef> places = new TreeMap<>();
        places.put(new RuleId("camp"), new PlaceKindDef(new RuleId("camp"), "Camp"));
        places.put(new RuleId("trail"), new PlaceKindDef(new RuleId("trail"), "Trail"));
        places.put(new RuleId("landmark"), new PlaceKindDef(new RuleId("landmark"), "Landmark"));

        TreeMap<RuleId, TaskKindDef> tasks = new TreeMap<>();
        tasks.put(new RuleId("wait"),
                new TaskKindDef(new RuleId("wait"), "Wait", 1_000L, 3_600_000L, 10));
        tasks.put(new RuleId("travel"),
                new TaskKindDef(new RuleId("travel"), "Travel", 0L, Long.MAX_VALUE, 1));
        return new RuleRegistry(places, tasks);
    }

    /** Place kinds in ascending {@link RuleId} order; unmodifiable view. */
    public SequencedMap<RuleId, PlaceKindDef> placeKinds() {
        return Collections.unmodifiableSequencedMap(placeKinds);
    }

    /** Task kinds in ascending {@link RuleId} order; unmodifiable view. */
    public SequencedMap<RuleId, TaskKindDef> taskKinds() {
        return Collections.unmodifiableSequencedMap(taskKinds);
    }
}
