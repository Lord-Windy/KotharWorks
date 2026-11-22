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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RuleRegistry} stock catalog ordering, unmodifiable views, and
 * constructor validation invariants.
 */
class RuleRegistryTest {

    @Test
    void minimalDefaultExposesSortedUnmodifiableCatalog() {
        RuleRegistry registry = RuleRegistry.minimalDefault();
        assertEquals(1, RuleRegistry.WILDS_RULES_VERSION);

        assertEquals(
                List.of(new RuleId("camp"), new RuleId("landmark"), new RuleId("trail")),
                List.copyOf(registry.placeKinds().keySet()));
        assertEquals(
                List.of(new RuleId("travel"), new RuleId("wait")),
                List.copyOf(registry.taskKinds().keySet()));

        TaskKindDef wait = registry.taskKinds().get(new RuleId("wait"));
        assertEquals(1_000L, wait.minDurationMs());
        assertEquals(3_600_000L, wait.maxDurationMs());
        assertEquals(10, wait.maxBatches());

        TaskKindDef travel = registry.taskKinds().get(new RuleId("travel"));
        assertEquals(1, travel.maxBatches());

        assertThrows(UnsupportedOperationException.class,
                () -> registry.placeKinds().put(new RuleId("ruin"),
                        new PlaceKindDef(new RuleId("ruin"), "Ruin")));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.taskKinds().remove(new RuleId("wait")));
    }

    @Test
    void defConstructorsValidateBoundsAndBlankNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaceKindDef(new RuleId("camp"), "   "));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskKindDef(new RuleId("wait"), "", 1_000L, 2_000L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskKindDef(new RuleId("wait"), "Wait", -1L, 2_000L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskKindDef(new RuleId("wait"), "Wait", 5_000L, 2_000L, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskKindDef(new RuleId("wait"), "Wait", 1_000L, 2_000L, 0));
    }

    @Test
    void registryRejectsMismatchedKeysAndNullEntries() {
        assertThrows(IllegalArgumentException.class, () -> new RuleRegistry(
                Map.of(new RuleId("camp"), new PlaceKindDef(new RuleId("trail"), "Trail")),
                Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RuleRegistry(
                Map.of(),
                Map.of(new RuleId("wait"),
                        new TaskKindDef(new RuleId("travel"), "Travel", 0L, 10L, 1))));

        Map<RuleId, PlaceKindDef> withNullValue = new HashMap<>();
        withNullValue.put(new RuleId("camp"), null);
        assertThrows(NullPointerException.class, () -> new RuleRegistry(withNullValue, Map.of()));
    }
}
