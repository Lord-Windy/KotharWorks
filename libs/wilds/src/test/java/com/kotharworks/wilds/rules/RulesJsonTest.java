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
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonArr;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Byte-level pins for {@link RulesJson}, the single canonical catalog writer behind
 * {@link RulesFingerprint} and {@code GET /api/v1/rules}.
 */
class RulesJsonTest {

    /**
     * Exactly what the pre-ticket-11 server serializer ({@code ObservationJson.rules})
     * emitted for {@code minimalDefault()} at rules version 1, with the closing brace
     * removed: the new output must start with this, byte for byte.
     */
    private static final String LEGACY_PREFIX = "{\"rulesVersion\":1,"
            + "\"placeKinds\":[{\"id\":\"camp\",\"name\":\"Camp\"},"
            + "{\"id\":\"landmark\",\"name\":\"Landmark\"},"
            + "{\"id\":\"trail\",\"name\":\"Trail\"}],"
            + "\"taskKinds\":[{\"id\":\"travel\",\"name\":\"Travel\",\"minDurationMs\":0,"
            + "\"maxDurationMs\":9223372036854775807,\"maxBatches\":1},"
            + "{\"id\":\"wait\",\"name\":\"Wait\",\"minDurationMs\":1000,"
            + "\"maxDurationMs\":3600000,\"maxBatches\":10}]";

    @Test
    void writeIsByteForBytePinnedForMinimalDefault() {
        assertEquals(LEGACY_PREFIX + ",\"rulesFingerprint\":\"0x26679a32edcffaaf\"}",
                RulesJson.write(RuleRegistry.minimalDefault(), 1));
    }

    @Test
    void writeIsTheLegacyShapePlusOneTrailingField() {
        String written = RulesJson.write(RuleRegistry.minimalDefault(), 1);
        assertTrue(written.startsWith(LEGACY_PREFIX), written);

        JsonObj parsed = assertInstanceOf(JsonObj.class, JsonCodec.parse(written));
        List<String> keys = new ArrayList<>();
        for (JsonObj.Entry entry : parsed.entries()) {
            keys.add(entry.key());
        }
        assertEquals(List.of("rulesVersion", "placeKinds", "taskKinds", "rulesFingerprint"), keys);
        assertEquals(new JsonCodec.JsonStr(
                        RulesFingerprint.toHex(RuleRegistry.minimalDefault().fingerprint())),
                parsed.getOrNull("rulesFingerprint"));
    }

    @Test
    void writeCarriesTheGivenRulesVersion() {
        String written = RulesJson.write(RuleRegistry.minimalDefault(), 7);
        assertTrue(written.startsWith("{\"rulesVersion\":7,\"placeKinds\":"), written);
        // The version is not part of the fingerprint.
        assertTrue(written.endsWith(",\"rulesFingerprint\":\"0x26679a32edcffaaf\"}"), written);
    }

    @Test
    void catalogExcludesRulesVersionAndFingerprint() {
        JsonObj catalog = RulesJson.catalog(RuleRegistry.minimalDefault());
        assertNull(catalog.getOrNull("rulesVersion"));
        assertNull(catalog.getOrNull("rulesFingerprint"));
        assertEquals(2, catalog.entries().size());
        assertEquals("placeKinds", catalog.entries().get(0).key());
        assertEquals("taskKinds", catalog.entries().get(1).key());

        String text = JsonCodec.write(catalog);
        assertFalse(text.contains("rulesVersion"), text);
        assertFalse(text.contains("rulesFingerprint"), text);
        assertEquals("{" + LEGACY_PREFIX.substring("{\"rulesVersion\":1,".length()) + "}", text);
    }

    @Test
    void everyRegistryDefinitionIsSerializedExactlyOnce() {
        RuleRegistry big = RuleRegistry.builder()
                .placeKind(new RuleRegistry.PlaceKindDef(new RuleId("camp"), "Camp"))
                .placeKind(new RuleRegistry.PlaceKindDef(new RuleId("ruin"), "Ruin"))
                .taskKind(new RuleRegistry.TaskKindDef(new RuleId("wait"), "Wait", 1L, 2L, 1))
                .taskKind(new RuleRegistry.TaskKindDef(new RuleId("dig"), "Dig", 3L, 4L, 2))
                .taskKind(new RuleRegistry.TaskKindDef(new RuleId("forage"), "Forage", 5L, 6L, 3))
                .build();
        for (RuleRegistry registry : List.of(RuleRegistry.minimalDefault(), big,
                RuleRegistry.builder().build())) {
            JsonObj catalog = RulesJson.catalog(registry);
            List<String> serializedIds = new ArrayList<>();
            for (JsonObj.Entry family : catalog.entries()) {
                for (JsonCodec.JsonValue entry : assertInstanceOf(JsonArr.class,
                        family.value()).values()) {
                    JsonCodec.JsonStr id = assertInstanceOf(JsonCodec.JsonStr.class,
                            assertInstanceOf(JsonObj.class, entry).getOrNull("id"));
                    serializedIds.add(id.value());
                }
            }
            List<String> registryIds = new ArrayList<>();
            for (RuleDef def : registry.definitions()) {
                registryIds.add(def.id().slug());
            }
            assertEquals(registry.definitions().size(), serializedIds.size());
            assertEquals(registryIds, serializedIds);
        }
    }

    @Test
    void extremeIntegersAndEscapedNamesRenderCanonically() {
        RuleRegistry registry = RuleRegistry.builder()
                .placeKind(new RuleRegistry.PlaceKindDef(new RuleId("cairn"), "The \"Caírn\""))
                .taskKind(new RuleRegistry.TaskKindDef(new RuleId("long_haul"), "Long Haul",
                        Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE))
                .build();
        assertEquals("{\"placeKinds\":[{\"id\":\"cairn\",\"name\":\"The \\\"Caírn\\\"\"}],"
                        + "\"taskKinds\":[{\"id\":\"long_haul\",\"name\":\"Long Haul\","
                        + "\"minDurationMs\":9223372036854775807,"
                        + "\"maxDurationMs\":9223372036854775807,\"maxBatches\":2147483647}]}",
                JsonCodec.write(RulesJson.catalog(registry)));
    }
}
