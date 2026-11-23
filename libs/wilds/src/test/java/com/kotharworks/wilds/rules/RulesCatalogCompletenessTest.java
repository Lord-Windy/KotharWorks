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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.kotharworks.wilds.id.RuleId;
import com.kotharworks.wilds.replay.JsonCodec.JsonArr;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonValue;

/**
 * Review finding 11-M2: the {@link RulesJson} switch is the only compile-time check,
 * and it fires only for a new {@code permits} type. A family left out of
 * {@code catalogEntries} or {@link RuleRegistry#definitions()}, or a def field left out
 * of its switch arm, compiles and silently skips the fingerprint. This test derives
 * every expectation by reflection from the sealed {@link RuleDef} hierarchy, so it has
 * no hand-maintained list to forget.
 *
 * <p>Conventions it enforces for each {@code permits} type {@code D}:
 * <ul>
 *   <li>{@code D} is a record;</li>
 *   <li>{@link RuleRegistry} has exactly one public no-arg accessor returning
 *       {@code SequencedMap<RuleId, D>}, and its name is the JSON family key;</li>
 *   <li>{@link RuleRegistry.Builder} has exactly one public method taking a {@code D};</li>
 *   <li>{@link RuleRegistry#minimalDefault()} has at least one {@code D}, so its fields
 *       are checked (add a stock entry with the family, or extend {@link #fixture}).</li>
 * </ul>
 */
class RulesCatalogCompletenessTest {

    private static final RuleRegistry FIXTURE = fixture();

    /** The registry the checks run against: every family must be non-empty here. */
    private static RuleRegistry fixture() {
        return RuleRegistry.minimalDefault();
    }

    @Test
    void everyPermittedDefIsARecordWithOneRegistryAccessorAndOneBuilderMethod() {
        for (Class<?> type : defTypes()) {
            assertTrue(type.isRecord(), type.getSimpleName() + " must be a record");
            accessorFor(type);
            builderMethodFor(type);
        }
    }

    @Test
    void catalogSerializesExactlyTheRegistryFamiliesWithEveryEntry() throws Exception {
        Map<String, JsonArr> catalog = families(RulesJson.catalog(FIXTURE));
        List<String> expectedKeys = new ArrayList<>();
        for (Class<?> type : defTypes()) {
            Method accessor = accessorFor(type);
            String key = accessor.getName();
            expectedKeys.add(key);
            assertTrue(catalog.containsKey(key), "RulesJson.catalog has no \"" + key
                    + "\" family for " + type.getSimpleName() + " (catalogEntries line missing?)");
            int size = ((SequencedMap<?, ?>) accessor.invoke(FIXTURE)).size();
            assertTrue(size > 0, type.getSimpleName() + " has no entry in the fixture registry;"
                    + " add one so its fields are checked");
            assertEquals(size, catalog.get(key).values().size(),
                    "\"" + key + "\" serializes a different map than " + key + "()");
        }
        assertEquals(new TreeSet<>(expectedKeys), new TreeSet<>(catalog.keySet()),
                "catalog families vs RuleDef permits");
    }

    @Test
    void definitionsListsEveryFamily() throws Exception {
        int total = 0;
        for (Class<?> type : defTypes()) {
            SequencedMap<?, ?> family = (SequencedMap<?, ?>) accessorFor(type).invoke(FIXTURE);
            total += family.size();
            for (Object def : family.values()) {
                assertTrue(FIXTURE.definitions().contains(def),
                        "definitions() misses " + type.getSimpleName() + " " + def);
            }
        }
        assertEquals(total, FIXTURE.definitions().size());
    }

    @Test
    void everyRecordComponentIsRenderedAsAJsonKeyInDeclarationOrder() throws Exception {
        Map<String, JsonArr> catalog = families(RulesJson.catalog(FIXTURE));
        for (Class<?> type : defTypes()) {
            List<String> components = Arrays.stream(type.getRecordComponents())
                    .map(RecordComponent::getName).toList();
            for (JsonValue rendered : catalog.get(accessorFor(type).getName()).values()) {
                List<String> keys = ((JsonObj) rendered).entries().stream()
                        .map(JsonObj.Entry::key).toList();
                assertEquals(components, keys, type.getSimpleName()
                        + " fields vs rendered keys (switch arm in RulesJson.entry out of date?)");
            }
        }
    }

    @Test
    void mutatingAnyComponentOfAnyDefMovesTheFingerprint() throws Exception {
        long base = FIXTURE.fingerprint();
        TreeSet<Long> seen = new TreeSet<>(List.of(base));
        int variants = 0;
        for (Class<?> type : defTypes()) {
            SequencedMap<?, ?> family = (SequencedMap<?, ?>) accessorFor(type).invoke(FIXTURE);
            for (Object def : family.values()) {
                for (int c = 0; c < type.getRecordComponents().length; c++) {
                    Object mutated = mutate(def, c);
                    String label = type.getSimpleName() + " " + def + " component "
                            + type.getRecordComponents()[c].getName();
                    long fp = rebuildWith(def, mutated).fingerprint();
                    assertNotEquals(base, fp, "fingerprint unchanged by " + label);
                    assertTrue(seen.add(fp), "fingerprint collides for " + label);
                    variants++;
                }
            }
        }
        assertTrue(variants > 0);
    }

    // ------------------------------------------------------------------ reflection rig

    private static List<Class<?>> defTypes() {
        Class<?>[] permitted = RuleDef.class.getPermittedSubclasses();
        assertTrue(permitted != null && permitted.length > 0, "RuleDef must be sealed");
        return List.of(permitted);
    }

    private static Method accessorFor(Class<?> type) {
        List<Method> matches = new ArrayList<>();
        for (Method m : RuleRegistry.class.getMethods()) {
            if (m.getParameterCount() == 0 && !Modifier.isStatic(m.getModifiers())
                    && SequencedMap.class.isAssignableFrom(m.getReturnType())
                    && m.getGenericReturnType() instanceof ParameterizedType p) {
                Type[] args = p.getActualTypeArguments();
                if (args.length == 2 && args[0] == RuleId.class && args[1] == type) {
                    matches.add(m);
                }
            }
        }
        if (matches.size() != 1) {
            fail("RuleRegistry needs exactly one SequencedMap<RuleId, " + type.getSimpleName()
                    + "> accessor, found " + matches);
        }
        return matches.getFirst();
    }

    private static Method builderMethodFor(Class<?> type) {
        List<Method> matches = Arrays.stream(RuleRegistry.Builder.class.getMethods())
                .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == type)
                .toList();
        if (matches.size() != 1) {
            fail("RuleRegistry.Builder needs exactly one method taking " + type.getSimpleName()
                    + ", found " + matches);
        }
        return matches.getFirst();
    }

    private static Map<String, JsonArr> families(JsonObj catalog) {
        Map<String, JsonArr> out = new LinkedHashMap<>();
        for (JsonObj.Entry e : catalog.entries()) {
            out.put(e.key(), (JsonArr) e.value());
        }
        return out;
    }

    /** The fixture registry with {@code original} swapped for {@code replacement}. */
    private static RuleRegistry rebuildWith(Object original, Object replacement)
            throws ReflectiveOperationException {
        RuleRegistry.Builder builder = RuleRegistry.builder();
        for (RuleDef def : FIXTURE.definitions()) {
            Object put = def.equals(original) ? replacement : def;
            builderMethodFor(def.getClass()).invoke(builder, put);
        }
        return builder.build();
    }

    /** A valid copy of the record {@code def} with only component {@code index} changed. */
    private static Object mutate(Object def, int index) throws ReflectiveOperationException {
        RecordComponent[] components = def.getClass().getRecordComponents();
        Object[] values = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = components[i].getAccessor().invoke(def);
            types[i] = components[i].getType();
        }
        Constructor<?> canonical = def.getClass().getDeclaredConstructor(types);
        List<Object> candidates = candidatesFor(values[index]);
        assertFalse(candidates.isEmpty(), "no mutation rule for component type "
                + types[index].getName() + " of " + def.getClass().getSimpleName()
                + "; extend candidatesFor");
        for (Object candidate : candidates) {
            Object[] changed = values.clone();
            changed[index] = candidate;
            try {
                return canonical.newInstance(changed);
            } catch (InvocationTargetException invalid) {
                // That value breaks the record's invariants; try the next one.
            }
        }
        throw new AssertionError("no valid mutation of " + components[index].getName()
                + " in " + def);
    }

    private static List<Object> candidatesFor(Object value) {
        return switch (value) {
            case RuleId id -> List.of(new RuleId(id.slug() + "_x"));
            case String s -> List.of(s + "!");
            case Long l -> List.of(l + 1, l - 1);
            case Integer i -> List.of(i + 1, i - 1);
            case Boolean b -> List.of(!b);
            default -> List.of();
        };
    }
}
