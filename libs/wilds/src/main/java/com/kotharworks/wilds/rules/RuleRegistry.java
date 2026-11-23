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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.TreeMap;

/**
 * Compile-time structural rule catalog: the closed set of place kinds and task kinds a
 * world runs under, keyed by {@link RuleId} and iterated in deterministic lexicographic
 * order ({@code TreeMap}, never hash-ordered). Immutable after construction; built with
 * {@link #builder()}.
 *
 * <p>Deliberately gameplay-neutral: no doctrines, skills, or combat tables — only the
 * structural vocabulary the neutral {@code WorldState} schema needs.
 *
 * <p>Every definition is a {@link RuleDef}. The whole catalog serializes through the single
 * canonical writer {@link RulesJson}, and {@link #fingerprint()} digests that output, so a
 * change to any field of any definition moves the fingerprint (pinned by
 * {@code RulesVersionPinTest}, which then demands a {@link #WILDS_RULES_VERSION} bump).
 */
public final class RuleRegistry {

    /**
     * Rules schema version carried by every {@code WorldState}; bump on any rule or
     * mechanic change (enforced by {@code RulesVersionPinTest}).
     */
    public static final int WILDS_RULES_VERSION = 1;

    /** Structural kind of a place (e.g. {@code camp}, {@code trail}, {@code landmark}). */
    public record PlaceKindDef(RuleId id, String name) implements RuleDef {
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
                              int maxBatches) implements RuleDef {
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

    /**
     * Accumulates definitions per family. Rejects null definitions
     * ({@link NullPointerException}) and duplicate ids within one family
     * ({@link IllegalArgumentException}); the same slug in different families is allowed.
     * Reusable: {@link #build()} copies, so later additions never alias a built registry.
     */
    public static final class Builder {

        private final TreeMap<RuleId, PlaceKindDef> placeKinds = new TreeMap<>();
        private final TreeMap<RuleId, TaskKindDef> taskKinds = new TreeMap<>();

        private Builder() {
        }

        /** Adds a place kind; its id must be new among place kinds. */
        public Builder placeKind(PlaceKindDef def) {
            Objects.requireNonNull(def, "placeKind def");
            putUnique(placeKinds, def, "placeKinds");
            return this;
        }

        /** Adds a task kind; its id must be new among task kinds. */
        public Builder taskKind(TaskKindDef def) {
            Objects.requireNonNull(def, "taskKind def");
            putUnique(taskKinds, def, "taskKinds");
            return this;
        }

        /** Builds an immutable registry from a defensive copy of the current contents. */
        public RuleRegistry build() {
            return new RuleRegistry(placeKinds, taskKinds);
        }

        private static <D extends RuleDef> void putUnique(TreeMap<RuleId, D> family, D def,
                                                          String familyName) {
            if (family.containsKey(def.id())) {
                throw new IllegalArgumentException("duplicate " + familyName + " id: "
                        + def.id().slug());
            }
            family.put(def.id(), def);
        }
    }

    private final TreeMap<RuleId, PlaceKindDef> placeKinds;
    private final TreeMap<RuleId, TaskKindDef> taskKinds;
    private final long fingerprint;

    private RuleRegistry(TreeMap<RuleId, PlaceKindDef> placeKinds,
                         TreeMap<RuleId, TaskKindDef> taskKinds) {
        this.placeKinds = new TreeMap<>(placeKinds);
        this.taskKinds = new TreeMap<>(taskKinds);
        // Eager: the registry is immutable, so the digest is computed exactly once.
        this.fingerprint = RulesFingerprint.of(this);
    }

    /** A fresh, empty {@link Builder}. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The stock structural catalog: place kinds {@code camp}/{@code trail}/{@code landmark}
     * and task kinds {@code wait}/{@code travel}.
     */
    public static RuleRegistry minimalDefault() {
        return builder()
                .placeKind(new PlaceKindDef(new RuleId("camp"), "Camp"))
                .placeKind(new PlaceKindDef(new RuleId("trail"), "Trail"))
                .placeKind(new PlaceKindDef(new RuleId("landmark"), "Landmark"))
                .taskKind(new TaskKindDef(new RuleId("wait"), "Wait", 1_000L, 3_600_000L, 10))
                .taskKind(new TaskKindDef(new RuleId("travel"), "Travel", 0L, Long.MAX_VALUE, 1))
                .build();
    }

    /** Place kinds in ascending {@link RuleId} order; unmodifiable view. */
    public SequencedMap<RuleId, PlaceKindDef> placeKinds() {
        return Collections.unmodifiableSequencedMap(placeKinds);
    }

    /** Task kinds in ascending {@link RuleId} order; unmodifiable view. */
    public SequencedMap<RuleId, TaskKindDef> taskKinds() {
        return Collections.unmodifiableSequencedMap(taskKinds);
    }

    /**
     * Every definition in fixed family order (place kinds, then task kinds), each family
     * ascending by {@link RuleId}; unmodifiable.
     */
    public SequencedCollection<RuleDef> definitions() {
        List<RuleDef> all = new ArrayList<>(placeKinds.size() + taskKinds.size());
        all.addAll(placeKinds.values());
        all.addAll(taskKinds.values());
        return Collections.unmodifiableList(all);
    }

    /**
     * Deterministic 64-bit digest of this catalog's canonical {@link RulesJson} form; see
     * {@link RulesFingerprint#of(RuleRegistry)}. Excludes {@link #WILDS_RULES_VERSION}.
     */
    public long fingerprint() {
        return fingerprint;
    }
}
