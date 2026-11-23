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
package com.kotharworks.systest.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.kotharworks.systest.support.Api;
import com.kotharworks.systest.support.Json;
import com.kotharworks.systest.support.World;

/**
 * The rules-version gate's public surface: {@code /api/v1/meta} (anonymous) and
 * {@code /api/v1/rules} (bot token) advertise the same rules version and the same
 * well-formed rules fingerprint, and the catalog keeps its established field names.
 */
@Tag("bot")
class RulesFingerprintLiveTest {

    private static final Pattern FINGERPRINT = Pattern.compile("^0x[0-9a-f]{16}$");

    @Test
    @DisplayName("meta and rules agree on rulesVersion and a well-formed rulesFingerprint")
    void metaAndRulesAgree() {
        World.BotSeat seat = World.botSeat("rules-fingerprint-bot");
        Json.Node rules = Api.bot(seat.token()).rules().ok();
        Json.Node meta = Api.anonymous().meta().ok();

        assertEquals(meta.get("rulesVersion").asLong(), rules.get("rulesVersion").asLong(),
                "rulesVersion differs between /meta and /rules");

        String metaFingerprint = meta.get("rulesFingerprint").text();
        String rulesFingerprint = rules.get("rulesFingerprint").text();
        assertTrue(metaFingerprint != null && FINGERPRINT.matcher(metaFingerprint).matches(),
                "malformed /meta rulesFingerprint: " + metaFingerprint);
        assertTrue(rulesFingerprint != null && FINGERPRINT.matcher(rulesFingerprint).matches(),
                "malformed /rules rulesFingerprint: " + rulesFingerprint);
        assertEquals(metaFingerprint, rulesFingerprint,
                "/meta and /rules disagree on the rules fingerprint");
    }

    @Test
    @DisplayName("the rules catalog still carries placeKinds and taskKinds with the old field names")
    void catalogShapeUnchanged() {
        World.BotSeat seat = World.botSeat("rules-fingerprint-bot");
        Json.Node rules = Api.bot(seat.token()).rules().ok();

        Json.Node placeKinds = rules.get("placeKinds");
        Json.Node taskKinds = rules.get("taskKinds");
        assertTrue(placeKinds.size() > 0, "placeKinds missing or empty: " + rules);
        assertTrue(taskKinds.size() > 0, "taskKinds missing or empty: " + rules);

        for (Json.Node place : placeKinds.list()) {
            for (String field : List.of("id", "name")) {
                assertTrue(place.has(field), "placeKind lacks \"" + field + "\": " + place);
            }
        }
        for (Json.Node task : taskKinds.list()) {
            for (String field : List.of("id", "name", "minDurationMs", "maxDurationMs",
                    "maxBatches")) {
                assertTrue(task.has(field), "taskKind lacks \"" + field + "\": " + task);
            }
        }
    }
}
