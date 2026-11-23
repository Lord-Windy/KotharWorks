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
package com.kotharworks.wilds.server.ports;

import java.util.Objects;

/**
 * Permanent metadata of one simulated world. The version triple (and the rules
 * fingerprint, when recorded) is asserted at every boot so a binary that cannot
 * safely replay an existing WAL refuses to start against it.
 *
 * @param worldId          stable world id (WAL/events/checkpoints key on it)
 * @param worldSeed        canonical seed the engine was (re)created with
 * @param rulesVersion     {@code RuleRegistry.WILDS_RULES_VERSION} at creation
 * @param engineVersion    {@code WildsEngine.API_VERSION} at creation
 * @param protocolVersion  server wire-protocol version at creation
 * @param rulesFingerprint {@code RuleRegistry.fingerprint()} of the creating binary, or
 *                         {@code null} for a legacy world created before the fingerprint
 *                         was recorded (such worlds are pinned by the version triple alone)
 * @param status           {@code ACTIVE} or {@code ARCHIVED}
 */
public record WorldRecord(int worldId, long worldSeed, int rulesVersion, int engineVersion,
        int protocolVersion, Long rulesFingerprint, String status) {

    /** Status of a world currently being simulated. */
    public static final String ACTIVE = "ACTIVE";

    /** Status of a world retired by a version-mismatch rollover; its rows stay intact. */
    public static final String ARCHIVED = "ARCHIVED";

    public WorldRecord {
        Objects.requireNonNull(status, "status");
        if (worldId < 1) {
            throw new IllegalArgumentException("worldId must be >= 1: " + worldId);
        }
    }
}
