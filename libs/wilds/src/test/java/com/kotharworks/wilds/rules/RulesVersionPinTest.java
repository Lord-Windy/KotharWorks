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

import com.kotharworks.wilds.replay.GoldenScenario;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Rules-version gate: a rule or mechanic change cannot reach {@code main} without bumping
 * {@link RuleRegistry#WILDS_RULES_VERSION}.
 *
 * <p><b>What the pin guards.</b> {@code golden/rules-version.pin} records the triple
 * {@code (rulesVersion, rulesFingerprint, goldenHash)}. The running build recomputes all
 * three: {@code WILDS_RULES_VERSION}, {@code RuleRegistry.minimalDefault().fingerprint()}
 * (catches catalog changes) and the final {@code WorldHash} of a fresh
 * {@link GoldenScenario#run()} (catches mechanic changes that leave the catalog alone).
 * The golden hash is computed here, never read from {@code minimal-spine.hash}, so a stale
 * or re-blessed golden file cannot hide a change and test order does not matter. A
 * content change at the pinned version fails the build — also under
 * {@code -DblessGolden=true}. A missing pin always fails, so deleting it is no bypass.
 *
 * <p><b>Bless workflow.</b> After an intentional rules or mechanics change: bump
 * {@code RuleRegistry.WILDS_RULES_VERSION}, then run
 * {@code mvnd test -pl libs/wilds -DblessGolden=true}, which rewrites this pin (and
 * {@code minimal-spine.hash} via {@code GoldenReplayTest}); commit both with the bump.
 *
 * <p><b>Scenario changes count too.</b> A change to the golden <em>scenario itself</em>
 * moves the golden hash and therefore also forces a version bump, even though no rule
 * changed. That is intended: versions are cheap and dev worlds are disposable until
 * Chronicle I, and a single unambiguous rule beats a judgement call about which hash
 * moves are "real".
 */
class RulesVersionPinTest {

    private static final Path PIN = Path.of("src", "test", "resources", "golden",
            "rules-version.pin");
    private static final Path GOLDEN = Path.of("src", "test", "resources", "golden",
            "minimal-spine.hash");

    @Test
    void rulesAndMechanicsMatchThePinnedRulesVersion() throws IOException {
        RulesPin current = new RulesPin(RuleRegistry.WILDS_RULES_VERSION,
                RuleRegistry.minimalDefault().fingerprint(), GoldenScenario.run());

        if (!Files.isRegularFile(PIN)) {
            fail("rules-version pin missing at " + PIN + ". Blessing never creates it; "
                    + "restore it from version control, or commit this content after a "
                    + "deliberate review:\n" + current.format());
            return;
        }
        RulesPin pinned = RulesPin.parse(Files.readString(PIN));
        boolean blessing = Boolean.getBoolean("blessGolden");

        switch (RulesPin.evaluate(pinned, current, blessing)) {
            case RulesPin.Pass ignored -> {
                if (!blessing) {
                    long goldenFile = Long.parseLong(Files.readString(GOLDEN).trim());
                    assertEquals(pinned.goldenHash(), goldenFile,
                            "minimal-spine.hash is stale relative to rules-version.pin");
                }
            }
            case RulesPin.Fail(String message) -> fail(message);
            case RulesPin.Bless(RulesPin newPin) -> Files.writeString(PIN, newPin.format());
        }
    }
}
