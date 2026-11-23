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

import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.JsonCodec.JsonArr;
import com.kotharworks.wilds.replay.JsonCodec.JsonInt;
import com.kotharworks.wilds.replay.JsonCodec.JsonObj;
import com.kotharworks.wilds.replay.JsonCodec.JsonStr;
import com.kotharworks.wilds.replay.JsonCodec.JsonValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The single canonical writer for a {@link RuleRegistry} catalog. Both
 * {@link RulesFingerprint} (via {@link #catalog}) and {@code GET /api/v1/rules} (via
 * {@link #write}) render through here, so a family or field can never reach one and not
 * the other.
 *
 * <p>Canonical by construction: families in a fixed order ({@code placeKinds}, then
 * {@code taskKinds}), entries ascending by {@link com.kotharworks.wilds.id.RuleId}
 * (the registry's {@code TreeMap} order), fixed field order per entry, integers as
 * {@link JsonInt} (signed 64-bit, locale-free) and the compact {@link JsonCodec} writer.
 * {@link #entry} is an exhaustive {@code switch} over the sealed {@link RuleDef} with no
 * {@code default}, so a new {@code permits} type with no switch arm fails compilation.
 * The rest (a family missing from {@code catalogEntries} or
 * {@link RuleRegistry#definitions()}, or a record field missing from its arm) still
 * compiles; {@code RulesCatalogCompletenessTest} catches those by reflection.
 */
public final class RulesJson {

    private RulesJson() {
    }

    /**
     * The catalog object {@code {"placeKinds":[...],"taskKinds":[...]}} without
     * {@code rulesVersion} or {@code rulesFingerprint}; this is the fingerprint input.
     */
    public static JsonObj catalog(RuleRegistry rules) {
        Objects.requireNonNull(rules, "rules");
        return new JsonObj(catalogEntries(rules));
    }

    /**
     * The full {@code GET /api/v1/rules} document, in exactly this key order:
     * {@code {"rulesVersion":N,"placeKinds":[...],"taskKinds":[...],"rulesFingerprint":"0x<16 hex>"}}.
     * {@code rulesFingerprint} comes last so the response stays byte-compatible with the
     * pre-fingerprint shape plus one trailing field.
     */
    public static String write(RuleRegistry rules, int rulesVersion) {
        Objects.requireNonNull(rules, "rules");
        List<JsonObj.Entry> entries = new ArrayList<>();
        entries.add(JsonObj.entry("rulesVersion", new JsonInt(rulesVersion)));
        entries.addAll(catalogEntries(rules));
        entries.add(JsonObj.entry("rulesFingerprint",
                new JsonStr(RulesFingerprint.toHex(rules.fingerprint()))));
        return JsonCodec.write(new JsonObj(entries));
    }

    private static List<JsonObj.Entry> catalogEntries(RuleRegistry rules) {
        return List.of(
                JsonObj.entry("placeKinds", family(rules.placeKinds().values())),
                JsonObj.entry("taskKinds", family(rules.taskKinds().values())));
    }

    private static JsonArr family(Collection<? extends RuleDef> defs) {
        List<JsonValue> rendered = new ArrayList<>(defs.size());
        for (RuleDef def : defs) {
            rendered.add(entry(def));
        }
        return new JsonArr(rendered);
    }

    private static JsonObj entry(RuleDef def) {
        return switch (def) {
            case RuleRegistry.PlaceKindDef p -> new JsonObj(List.of(
                    JsonObj.entry("id", new JsonStr(p.id().slug())),
                    JsonObj.entry("name", new JsonStr(p.name()))));
            case RuleRegistry.TaskKindDef t -> new JsonObj(List.of(
                    JsonObj.entry("id", new JsonStr(t.id().slug())),
                    JsonObj.entry("name", new JsonStr(t.name())),
                    JsonObj.entry("minDurationMs", new JsonInt(t.minDurationMs())),
                    JsonObj.entry("maxDurationMs", new JsonInt(t.maxDurationMs())),
                    JsonObj.entry("maxBatches", new JsonInt(t.maxBatches()))));
        };
    }
}
