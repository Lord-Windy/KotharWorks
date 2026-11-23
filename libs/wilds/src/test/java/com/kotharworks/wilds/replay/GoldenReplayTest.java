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
package com.kotharworks.wilds.replay;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden replay harness: a fully scripted multi-owner scenario (chain travel, fail-soft
 * policies, multi-batch waits, active cancellation, integer permille energy penalty)
 * must land on exactly the blessed final {@code WorldHash}. Re-bless with
 * {@code -DblessGolden=true} after an intentional determinism-relevant change (which also
 * requires a {@code WILDS_RULES_VERSION} bump; see {@code RulesVersionPinTest}). The
 * scenario itself lives in {@link GoldenScenario}.
 */
class GoldenReplayTest {

    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden",
            "minimal-spine.hash");

    @Test
    void scriptedScenarioReproducesBlessedWorldHash() throws IOException {
        long finalHash = GoldenScenario.run();

        if (Boolean.getBoolean("blessGolden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, Long.toString(finalHash) + "\n");
        }
        assertTrue(Files.isRegularFile(GOLDEN),
                "golden hash file missing; run with -DblessGolden=true to bless it");
        long blessed = Long.parseLong(Files.readString(GOLDEN).trim());
        assertEquals(blessed, finalHash,
                "final world hash drifted from the blessed golden value");
    }
}
