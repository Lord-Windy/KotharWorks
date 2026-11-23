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

/**
 * Closed family of rule definitions held by a {@link RuleRegistry}: one record type per
 * content family, each keyed by a {@link RuleId} unique within its family.
 *
 * <p>Phase 1 extends the {@code permits} list (items, skills, techs, buildings…). Every
 * new family must also be added to {@link RuleRegistry.Builder} (a family adder plus its
 * own ascending-{@code RuleId} storage) and to {@link RulesJson} (whose exhaustive
 * {@code switch} over {@code RuleDef} stops compiling until the family is serialized), so
 * it automatically reaches {@link RulesFingerprint} and {@code GET /api/v1/rules}.
 */
public sealed interface RuleDef permits RuleRegistry.PlaceKindDef, RuleRegistry.TaskKindDef {

    /** The definition's identifier, unique within its family. */
    RuleId id();
}
