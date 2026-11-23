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
import com.kotharworks.wilds.rules.RuleRegistry.PlaceKindDef;
import com.kotharworks.wilds.rules.RuleRegistry.TaskKindDef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RuleRegistry}: stock catalog content, builder validation,
 * ascending iteration regardless of insertion order, {@code definitions()} order and
 * unmodifiable views.
 */
class RuleRegistryTest {

    private static PlaceKindDef place(String slug, String name) {
        return new PlaceKindDef(new RuleId(slug), name);
    }

    private static TaskKindDef task(String slug, String name, long min, long max, int batches) {
        return new TaskKindDef(new RuleId(slug), name, min, max, batches);
    }

    private static List<RuleId> ids(String... slugs) {
        List<RuleId> ids = new ArrayList<>();
        for (String slug : slugs) {
            ids.add(new RuleId(slug));
        }
        return ids;
    }

    @Test
    void minimalDefaultContentIsUnchangedFieldByField() {
        RuleRegistry registry = RuleRegistry.minimalDefault();
        assertEquals(1, RuleRegistry.WILDS_RULES_VERSION);

        assertEquals(List.of(place("camp", "Camp"), place("landmark", "Landmark"),
                        place("trail", "Trail")),
                List.copyOf(registry.placeKinds().values()));
        assertEquals(ids("camp", "landmark", "trail"),
                List.copyOf(registry.placeKinds().keySet()));
        assertEquals("Camp", registry.placeKinds().get(new RuleId("camp")).name());
        assertEquals("Landmark", registry.placeKinds().get(new RuleId("landmark")).name());
        assertEquals("Trail", registry.placeKinds().get(new RuleId("trail")).name());

        assertEquals(ids("travel", "wait"), List.copyOf(registry.taskKinds().keySet()));
        TaskKindDef travel = registry.taskKinds().get(new RuleId("travel"));
        assertEquals(new RuleId("travel"), travel.id());
        assertEquals("Travel", travel.name());
        assertEquals(0L, travel.minDurationMs());
        assertEquals(Long.MAX_VALUE, travel.maxDurationMs());
        assertEquals(1, travel.maxBatches());

        TaskKindDef wait = registry.taskKinds().get(new RuleId("wait"));
        assertEquals(new RuleId("wait"), wait.id());
        assertEquals("Wait", wait.name());
        assertEquals(1_000L, wait.minDurationMs());
        assertEquals(3_600_000L, wait.maxDurationMs());
        assertEquals(10, wait.maxBatches());
    }

    @Test
    void viewsAreUnmodifiable() {
        RuleRegistry registry = RuleRegistry.minimalDefault();
        assertThrows(UnsupportedOperationException.class,
                () -> registry.placeKinds().put(new RuleId("ruin"), place("ruin", "Ruin")));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.taskKinds().remove(new RuleId("wait")));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.definitions().add(place("ruin", "Ruin")));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.definitions().removeFirst());
    }

    @Test
    void defConstructorsValidateBoundsAndBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> place("camp", "   "));
        assertThrows(IllegalArgumentException.class, () -> task("wait", "", 1_000L, 2_000L, 1));
        assertThrows(IllegalArgumentException.class, () -> task("wait", "Wait", -1L, 2_000L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> task("wait", "Wait", 5_000L, 2_000L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> task("wait", "Wait", 1_000L, 2_000L, 0));
    }

    @Test
    void builderRejectsDuplicatePlaceKindIdNamingFamilyAndSlug() {
        RuleRegistry.Builder builder = RuleRegistry.builder().placeKind(place("camp", "Camp"));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.placeKind(place("camp", "Other Camp")));
        assertTrue(thrown.getMessage().contains("placeKinds"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("camp"), thrown.getMessage());
    }

    @Test
    void builderRejectsDuplicateTaskKindIdNamingFamilyAndSlug() {
        RuleRegistry.Builder builder = RuleRegistry.builder()
                .taskKind(task("wait", "Wait", 1L, 2L, 1));
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> builder.taskKind(task("wait", "Wait", 1L, 2L, 1)));
        assertTrue(thrown.getMessage().contains("taskKinds"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("wait"), thrown.getMessage());
    }

    @Test
    void builderRejectsNullDefs() {
        assertThrows(NullPointerException.class, () -> RuleRegistry.builder().placeKind(null));
        assertThrows(NullPointerException.class, () -> RuleRegistry.builder().taskKind(null));
    }

    @Test
    void sameSlugInDifferentFamiliesIsAllowed() {
        RuleRegistry registry = RuleRegistry.builder()
                .placeKind(place("camp", "Camp"))
                .taskKind(task("camp", "Make Camp", 1L, 2L, 1))
                .build();
        assertEquals(1, registry.placeKinds().size());
        assertEquals(1, registry.taskKinds().size());
        assertEquals(2, registry.definitions().size());
    }

    @Test
    void iterationIsAscendingRegardlessOfInsertionOrder() {
        RuleRegistry reversed = RuleRegistry.builder()
                .placeKind(place("trail", "Trail"))
                .taskKind(task("wait", "Wait", 1_000L, 3_600_000L, 10))
                .placeKind(place("landmark", "Landmark"))
                .taskKind(task("travel", "Travel", 0L, Long.MAX_VALUE, 1))
                .placeKind(place("camp", "Camp"))
                .build();
        RuleRegistry shuffled = RuleRegistry.builder()
                .placeKind(place("landmark", "Landmark"))
                .placeKind(place("camp", "Camp"))
                .taskKind(task("travel", "Travel", 0L, Long.MAX_VALUE, 1))
                .placeKind(place("trail", "Trail"))
                .taskKind(task("wait", "Wait", 1_000L, 3_600_000L, 10))
                .build();
        RuleRegistry stock = RuleRegistry.minimalDefault();
        for (RuleRegistry registry : List.of(reversed, shuffled)) {
            assertEquals(ids("camp", "landmark", "trail"),
                    List.copyOf(registry.placeKinds().keySet()));
            assertEquals(ids("travel", "wait"), List.copyOf(registry.taskKinds().keySet()));
            assertEquals(List.copyOf(stock.definitions()), List.copyOf(registry.definitions()));
        }

        RuleRegistry manyTasks = RuleRegistry.builder()
                .taskKind(task("zeta", "Zeta", 1L, 2L, 1))
                .taskKind(task("alpha", "Alpha", 1L, 2L, 1))
                .taskKind(task("mid_2", "Mid", 1L, 2L, 1))
                .taskKind(task("mid", "Mid", 1L, 2L, 1))
                .build();
        assertEquals(ids("alpha", "mid", "mid_2", "zeta"),
                List.copyOf(manyTasks.taskKinds().keySet()));
    }

    @Test
    void definitionsListPlaceKindsThenTaskKindsEachAscending() {
        List<RuleDef> expected = List.of(
                place("camp", "Camp"),
                place("landmark", "Landmark"),
                place("trail", "Trail"),
                task("travel", "Travel", 0L, Long.MAX_VALUE, 1),
                task("wait", "Wait", 1_000L, 3_600_000L, 10));
        assertEquals(expected, List.copyOf(RuleRegistry.minimalDefault().definitions()));

        // Family order wins over slug order: task "aaa" still follows place "zzz".
        RuleRegistry crossFamily = RuleRegistry.builder()
                .taskKind(task("aaa", "Aaa", 1L, 2L, 1))
                .placeKind(place("zzz", "Zzz"))
                .build();
        assertEquals(List.of(place("zzz", "Zzz"), task("aaa", "Aaa", 1L, 2L, 1)),
                List.copyOf(crossFamily.definitions()));
    }

    @Test
    void builderIsReusableWithoutAliasingBuiltRegistries() {
        RuleRegistry.Builder builder = RuleRegistry.builder().placeKind(place("camp", "Camp"));
        RuleRegistry first = builder.build();
        builder.placeKind(place("trail", "Trail"));
        RuleRegistry second = builder.build();

        assertEquals(ids("camp"), List.copyOf(first.placeKinds().keySet()));
        assertEquals(ids("camp", "trail"), List.copyOf(second.placeKinds().keySet()));
        assertNotEquals(first.fingerprint(), second.fingerprint());
        assertEquals(first.fingerprint(), RulesFingerprint.of(first));
    }

    @Test
    void emptyRegistryIsValid() {
        RuleRegistry empty = RuleRegistry.builder().build();
        assertTrue(empty.placeKinds().isEmpty());
        assertTrue(empty.taskKinds().isEmpty());
        assertTrue(empty.definitions().isEmpty());
    }
}
