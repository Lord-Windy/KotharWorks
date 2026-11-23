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

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure decision logic behind {@link RulesVersionPinTest}: the pinned
 * {@code (rulesVersion, rulesFingerprint, goldenHash)} triple, its strict text format, and
 * the verdict that turns "rules or mechanics changed without a version bump" into a build
 * failure. No I/O; the test owns reading and writing the pin file.
 *
 * @param rulesVersion     {@code RuleRegistry.WILDS_RULES_VERSION}
 * @param rulesFingerprint {@code RuleRegistry.minimalDefault().fingerprint()}
 * @param goldenHash       final {@code WorldHash} of the golden scenario
 */
record RulesPin(int rulesVersion, long rulesFingerprint, long goldenHash) {

    static final String BLESS_COMMAND = "mvnd test -pl libs/wilds -DblessGolden=true";

    private static final String VERSION_KEY = "rulesVersion=";
    private static final String FINGERPRINT_KEY = "rulesFingerprint=";
    private static final String GOLDEN_KEY = "goldenHash=";
    private static final Pattern VERSION_DIGITS = Pattern.compile("0|[1-9][0-9]{0,9}");
    private static final Pattern LONG_DIGITS = Pattern.compile("-?(0|[1-9][0-9]{0,18})");

    /** Outcome of comparing the committed pin against the running build. */
    sealed interface Verdict permits Pass, Fail, Bless {
    }

    /** The pin matches; nothing to do. */
    record Pass() implements Verdict {
    }

    /** The build must fail with {@code message}. */
    record Fail(String message) implements Verdict {
        Fail {
            Objects.requireNonNull(message, "message");
        }
    }

    /** Blessing is allowed: rewrite the pin file with {@code newPin}. */
    record Bless(RulesPin newPin) implements Verdict {
        Bless {
            Objects.requireNonNull(newPin, "newPin");
        }
    }

    /**
     * Strictly parses exactly the three lines {@code rulesVersion=N},
     * {@code rulesFingerprint=0x<16 lowercase hex>}, {@code goldenHash=<decimal long>}, in
     * that order, with at most one trailing newline; anything else is
     * {@link IllegalArgumentException}.
     */
    static RulesPin parse(String text) {
        Objects.requireNonNull(text, "text");
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        String[] lines = body.split("\n", -1);
        if (lines.length != 3) {
            throw new IllegalArgumentException(
                    "rules-version.pin must have exactly 3 lines, found " + lines.length);
        }
        String version = value(lines[0], VERSION_KEY);
        if (!VERSION_DIGITS.matcher(version).matches()) {
            throw new IllegalArgumentException("malformed rulesVersion: \"" + version + "\"");
        }
        int rulesVersion;
        try {
            rulesVersion = Integer.parseInt(version);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("rulesVersion out of range: \"" + version + "\"",
                    overflow);
        }
        long fingerprint = RulesFingerprint.parseHex(value(lines[1], FINGERPRINT_KEY));
        String golden = value(lines[2], GOLDEN_KEY);
        if (!LONG_DIGITS.matcher(golden).matches()) {
            throw new IllegalArgumentException("malformed goldenHash: \"" + golden + "\"");
        }
        long goldenHash;
        try {
            goldenHash = Long.parseLong(golden);
        } catch (NumberFormatException overflow) {
            throw new IllegalArgumentException("goldenHash out of range: \"" + golden + "\"",
                    overflow);
        }
        return new RulesPin(rulesVersion, fingerprint, goldenHash);
    }

    /** Canonical file form: the three {@code key=value} lines, each ending in {@code \n}. */
    String format() {
        return VERSION_KEY + rulesVersion + "\n"
                + FINGERPRINT_KEY + RulesFingerprint.toHex(rulesFingerprint) + "\n"
                + GOLDEN_KEY + goldenHash + "\n";
    }

    /**
     * Decides what the pin test does.
     * <ol>
     *   <li>pin equals current: {@link Pass} (even when blessing);</li>
     *   <li>content differs at the pinned version: {@link Fail} "bump the version", even
     *       when blessing — blessing never accepts a content change at an unchanged
     *       version;</li>
     *   <li>version went backwards: {@link Fail};</li>
     *   <li>version bumped but not blessing: {@link Fail} telling the developer to bless;</li>
     *   <li>version bumped and blessing: {@link Bless} with {@code current}.</li>
     * </ol>
     */
    static Verdict evaluate(RulesPin pinned, RulesPin current, boolean blessing) {
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(current, "current");
        if (pinned.equals(current)) {
            return new Pass();
        }
        if (current.rulesVersion() == pinned.rulesVersion()) {
            return new Fail("Rules or mechanics changed without bumping "
                    + "RuleRegistry.WILDS_RULES_VERSION (pinned " + pinned.rulesVersion()
                    + "). Bump it, then run `" + BLESS_COMMAND + "`."
                    + differences(pinned, current));
        }
        if (current.rulesVersion() < pinned.rulesVersion()) {
            return new Fail("RuleRegistry.WILDS_RULES_VERSION went backwards: rules version "
                    + current.rulesVersion() + " is below the pinned " + pinned.rulesVersion()
                    + "." + differences(pinned, current));
        }
        if (!blessing) {
            return new Fail("RuleRegistry.WILDS_RULES_VERSION was bumped to "
                    + current.rulesVersion() + " (pinned " + pinned.rulesVersion()
                    + ") but rules-version.pin was not re-blessed. Re-run with "
                    + "-DblessGolden=true: `" + BLESS_COMMAND + "`."
                    + differences(pinned, current));
        }
        return new Bless(current);
    }

    private static String differences(RulesPin pinned, RulesPin current) {
        return "\n  rulesVersion:     pinned " + pinned.rulesVersion()
                + ", current " + current.rulesVersion()
                + "\n  rulesFingerprint: pinned " + RulesFingerprint.toHex(pinned.rulesFingerprint())
                + ", current " + RulesFingerprint.toHex(current.rulesFingerprint())
                + "\n  goldenHash:       pinned " + pinned.goldenHash()
                + ", current " + current.goldenHash();
    }

    private static String value(String line, String key) {
        if (!line.startsWith(key)) {
            throw new IllegalArgumentException(
                    "expected a line starting with \"" + key + "\", found \"" + line + "\"");
        }
        return line.substring(key.length());
    }
}
