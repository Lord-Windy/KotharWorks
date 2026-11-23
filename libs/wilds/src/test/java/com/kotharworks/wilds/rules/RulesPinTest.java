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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure-logic tests for {@link RulesPin}: every verdict branch plus the strict file format. */
class RulesPinTest {

    private static final RulesPin PINNED = new RulesPin(1, 0x26679a32edcffaafL,
            6803313659441641466L);
    private static final String BUMP_MESSAGE = "Rules or mechanics changed without bumping "
            + "RuleRegistry.WILDS_RULES_VERSION (pinned 1). Bump it, then run "
            + "`mvnd test -pl libs/wilds -DblessGolden=true`.";

    private static RulesPin withFingerprint(RulesPin pin, long fingerprint) {
        return new RulesPin(pin.rulesVersion(), fingerprint, pin.goldenHash());
    }

    private static RulesPin withGolden(RulesPin pin, long golden) {
        return new RulesPin(pin.rulesVersion(), pin.rulesFingerprint(), golden);
    }

    private static RulesPin withVersion(RulesPin pin, int version) {
        return new RulesPin(version, pin.rulesFingerprint(), pin.goldenHash());
    }

    // (i) pin == current -> Pass

    @Test
    void identicalPinPassesWithAndWithoutBlessing() {
        RulesPin current = new RulesPin(1, 0x26679a32edcffaafL, 6803313659441641466L);
        assertInstanceOf(RulesPin.Pass.class, RulesPin.evaluate(PINNED, current, false));
        assertInstanceOf(RulesPin.Pass.class, RulesPin.evaluate(PINNED, current, true));
    }

    // (ii) content changed at the pinned version -> Fail, even when blessing

    @Test
    void fingerprintChangeAtUnchangedVersionFailsWithBumpMessage() {
        RulesPin current = withFingerprint(PINNED, 0x1234L);
        RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                RulesPin.evaluate(PINNED, current, false));
        assertTrue(fail.message().startsWith(BUMP_MESSAGE + "\n"), fail.message());
        assertTrue(fail.message().contains("0x26679a32edcffaaf"), fail.message());
        assertTrue(fail.message().contains("0x0000000000001234"), fail.message());
    }

    @Test
    void goldenHashChangeAtUnchangedVersionFailsWithBumpMessage() {
        RulesPin current = withGolden(PINNED, -42L);
        RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                RulesPin.evaluate(PINNED, current, false));
        assertTrue(fail.message().startsWith(BUMP_MESSAGE + "\n"), fail.message());
        assertTrue(fail.message().contains("6803313659441641466"), fail.message());
        assertTrue(fail.message().contains("-42"), fail.message());
    }

    @Test
    void blessingRefusesAFingerprintChangeAtAnUnchangedVersion() {
        RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                RulesPin.evaluate(PINNED, withFingerprint(PINNED, 0x1234L), true));
        assertTrue(fail.message().startsWith(BUMP_MESSAGE), fail.message());
    }

    @Test
    void blessingRefusesAGoldenHashChangeAtAnUnchangedVersion() {
        RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                RulesPin.evaluate(PINNED, withGolden(PINNED, 99L), true));
        assertTrue(fail.message().startsWith(BUMP_MESSAGE), fail.message());
    }

    @Test
    void bumpMessageNamesThePinnedVersion() {
        RulesPin pinnedSeven = withVersion(PINNED, 7);
        RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                RulesPin.evaluate(pinnedSeven, withGolden(pinnedSeven, 1L), true));
        assertTrue(fail.message().contains("(pinned 7)"), fail.message());
    }

    // (iii) version went backwards -> Fail

    @Test
    void versionGoingBackwardsFailsEvenWhenBlessing() {
        RulesPin pinnedTwo = withVersion(PINNED, 2);
        for (RulesPin current : List.of(withVersion(PINNED, 1),
                withFingerprint(withVersion(PINNED, 1), 5L))) {
            for (boolean blessing : new boolean[] {false, true}) {
                RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                        RulesPin.evaluate(pinnedTwo, current, blessing));
                assertTrue(fail.message().contains("went backwards"), fail.message());
            }
        }
    }

    // (iv) something differs and not blessing -> Fail telling the developer to bless

    @Test
    void bumpedVersionWithoutBlessingFailsAskingToBless() {
        for (RulesPin current : List.of(
                withVersion(PINNED, 2),
                withFingerprint(withVersion(PINNED, 2), 5L),
                withGolden(withVersion(PINNED, 2), 5L))) {
            RulesPin.Fail fail = assertInstanceOf(RulesPin.Fail.class,
                    RulesPin.evaluate(PINNED, current, false));
            assertTrue(fail.message().contains("-DblessGolden=true"), fail.message());
            assertTrue(!fail.message().startsWith("Rules or mechanics changed"), fail.message());
        }
    }

    // (v) blessing and version bumped -> Bless(current)

    @Test
    void blessingAfterABumpRewritesThePin() {
        for (RulesPin current : List.of(
                withVersion(PINNED, 2),
                withFingerprint(withVersion(PINNED, 2), 5L),
                withGolden(withVersion(PINNED, 2), 5L),
                new RulesPin(9, -1L, Long.MIN_VALUE))) {
            RulesPin.Bless bless = assertInstanceOf(RulesPin.Bless.class,
                    RulesPin.evaluate(PINNED, current, true));
            assertEquals(current, bless.newPin());
        }
    }

    // File format

    @Test
    void formatIsExactlyThreeLines() {
        assertEquals("rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\n"
                + "goldenHash=6803313659441641466\n", PINNED.format());
        assertEquals("rulesVersion=0\nrulesFingerprint=0x8000000000000000\n"
                        + "goldenHash=-9223372036854775808\n",
                new RulesPin(0, Long.MIN_VALUE, Long.MIN_VALUE).format());
    }

    @Test
    void parseFormatRoundTrips() {
        for (RulesPin pin : List.of(PINNED, new RulesPin(0, 0L, 0L),
                new RulesPin(Integer.MAX_VALUE, -1L, Long.MIN_VALUE),
                new RulesPin(3, Long.MAX_VALUE, Long.MAX_VALUE))) {
            assertEquals(pin, RulesPin.parse(pin.format()));
        }
        // The trailing newline is optional.
        assertEquals(PINNED, RulesPin.parse("rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\n"
                + "goldenHash=6803313659441641466"));
    }

    @Test
    void parseRejectsAnythingElse() {
        String good = PINNED.format();
        for (String bad : List.of(
                "",
                "\n",
                good + "\n",
                good + "extra=1\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\n",
                "rulesFingerprint=0x26679a32edcffaaf\nrulesVersion=1\ngoldenHash=6803313659441641466\n",
                "rulesVersion=1\r\nrulesFingerprint=0x26679a32edcffaaf\r\ngoldenHash=6803313659441641466\r\n",
                " rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion = 1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=01\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=+1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=-1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=9999999999\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=1\n",
                "rulesVersion=1\nrulesFingerprint=26679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=1\nrulesFingerprint=0x26679A32EDCFFAAF\ngoldenHash=6803313659441641466\n",
                "rulesVersion=1\nrulesFingerprint=0x6679a32edcffaaf\ngoldenHash=6803313659441641466\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=+5\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=05\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=-0x5\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=9223372036854775808\n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\ngoldenHash=6803313659441641466 \n",
                "rulesVersion=1\nrulesFingerprint=0x26679a32edcffaaf\nGoldenHash=6803313659441641466\n")) {
            assertThrows(IllegalArgumentException.class, () -> RulesPin.parse(bad),
                    () -> "accepted:\n" + bad);
        }
    }
}
