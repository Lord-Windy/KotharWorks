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
import com.kotharworks.wilds.rules.RuleRegistry.PlaceKindDef;
import com.kotharworks.wilds.rules.RuleRegistry.TaskKindDef;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RulesFingerprint}: stable across insertion orders and runs, sensitive to every
 * field of every definition, independent of {@code rulesVersion}, and pinned for
 * {@code minimalDefault()}.
 */
class RulesFingerprintTest {

    /** Pinned fingerprint of {@code RuleRegistry.minimalDefault()}; matches rules-version.pin. */
    private static final long MINIMAL_DEFAULT_FINGERPRINT = 0x26679a32edcffaafL;

    private static RuleRegistry fromDefs(List<RuleDef> defs) {
        RuleRegistry.Builder builder = RuleRegistry.builder();
        for (RuleDef def : defs) {
            switch (def) {
                case PlaceKindDef p -> builder.placeKind(p);
                case TaskKindDef t -> builder.taskKind(t);
            }
        }
        return builder.build();
    }

    /** {@code minimalDefault()} with the definition at {@code index} replaced. */
    private static RuleRegistry mutateAt(int index, UnaryOperator<RuleDef> mutation) {
        List<RuleDef> defs = new ArrayList<>(RuleRegistry.minimalDefault().definitions());
        defs.set(index, mutation.apply(defs.get(index)));
        return fromDefs(defs);
    }

    @Test
    void pinnedLiteralForMinimalDefault() {
        assertEquals(0x26679a32edcffaafL, RuleRegistry.minimalDefault().fingerprint());
        assertEquals("0x26679a32edcffaaf",
                RulesFingerprint.toHex(RuleRegistry.minimalDefault().fingerprint()));
    }

    @Test
    void stableAcrossInsertionOrdersAndRepeatedRuns() {
        List<RuleDef> defs = new ArrayList<>(RuleRegistry.minimalDefault().definitions());
        List<RuleDef> reversed = new ArrayList<>(defs.reversed());
        List<RuleDef> interleaved = List.of(defs.get(4), defs.get(0), defs.get(3), defs.get(2),
                defs.get(1));
        for (int run = 0; run < 5; run++) {
            assertEquals(MINIMAL_DEFAULT_FINGERPRINT, RuleRegistry.minimalDefault().fingerprint());
            assertEquals(MINIMAL_DEFAULT_FINGERPRINT, fromDefs(defs).fingerprint());
            assertEquals(MINIMAL_DEFAULT_FINGERPRINT, fromDefs(reversed).fingerprint());
            assertEquals(MINIMAL_DEFAULT_FINGERPRINT, fromDefs(interleaved).fingerprint());
            assertEquals(MINIMAL_DEFAULT_FINGERPRINT, RulesFingerprint.of(fromDefs(reversed)));
        }
    }

    @Test
    void everySingleFieldOfEveryDefMovesTheFingerprint() {
        List<RuleDef> base = List.copyOf(RuleRegistry.minimalDefault().definitions());
        List<RuleRegistry> variants = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < base.size(); i++) {
            switch (base.get(i)) {
                case PlaceKindDef p -> {
                    variants.add(mutateAt(i, d -> new PlaceKindDef(
                            new RuleId(p.id().slug() + "_x"), p.name())));
                    labels.add(p.id().slug() + ".id");
                    variants.add(mutateAt(i, d -> new PlaceKindDef(p.id(), p.name() + "!")));
                    labels.add(p.id().slug() + ".name");
                }
                case TaskKindDef t -> {
                    variants.add(mutateAt(i, d -> new TaskKindDef(new RuleId(t.id().slug() + "_x"),
                            t.name(), t.minDurationMs(), t.maxDurationMs(), t.maxBatches())));
                    labels.add(t.id().slug() + ".id");
                    variants.add(mutateAt(i, d -> new TaskKindDef(t.id(), t.name() + "!",
                            t.minDurationMs(), t.maxDurationMs(), t.maxBatches())));
                    labels.add(t.id().slug() + ".name");
                    variants.add(mutateAt(i, d -> new TaskKindDef(t.id(), t.name(),
                            t.minDurationMs() + 1, Math.max(t.maxDurationMs(), t.minDurationMs() + 1),
                            t.maxBatches())));
                    labels.add(t.id().slug() + ".minDurationMs");
                    variants.add(mutateAt(i, d -> new TaskKindDef(t.id(), t.name(),
                            t.minDurationMs(), t.maxDurationMs() - 1, t.maxBatches())));
                    labels.add(t.id().slug() + ".maxDurationMs");
                    variants.add(mutateAt(i, d -> new TaskKindDef(t.id(), t.name(),
                            t.minDurationMs(), t.maxDurationMs(), t.maxBatches() + 1)));
                    labels.add(t.id().slug() + ".maxBatches");
                }
            }
        }
        // 3 place kinds x 2 fields + 2 task kinds x 5 fields.
        assertEquals(3 * 2 + 2 * 5, variants.size());

        TreeSet<Long> seen = new TreeSet<>();
        seen.add(MINIMAL_DEFAULT_FINGERPRINT);
        for (int v = 0; v < variants.size(); v++) {
            long fp = variants.get(v).fingerprint();
            assertNotEquals(MINIMAL_DEFAULT_FINGERPRINT, fp, "unchanged by " + labels.get(v));
            assertTrue(seen.add(fp), "collides with another variant: " + labels.get(v));
        }
    }

    @Test
    void addingOrRemovingADefMovesTheFingerprint() {
        List<RuleDef> base = new ArrayList<>(RuleRegistry.minimalDefault().definitions());

        List<RuleDef> withPlace = new ArrayList<>(base);
        withPlace.add(new PlaceKindDef(new RuleId("ruin"), "Ruin"));
        List<RuleDef> withTask = new ArrayList<>(base);
        withTask.add(new TaskKindDef(new RuleId("dig"), "Dig", 1L, 2L, 1));

        TreeSet<Long> seen = new TreeSet<>();
        seen.add(MINIMAL_DEFAULT_FINGERPRINT);
        assertTrue(seen.add(fromDefs(withPlace).fingerprint()));
        assertTrue(seen.add(fromDefs(withTask).fingerprint()));
        for (int i = 0; i < base.size(); i++) {
            List<RuleDef> without = new ArrayList<>(base);
            RuleDef removed = without.remove(i);
            assertTrue(seen.add(fromDefs(without).fingerprint()),
                    "removing " + removed.id().slug());
        }
        assertTrue(seen.add(RuleRegistry.builder().build().fingerprint()));
    }

    @Test
    void moveBetweenFamiliesMovesTheFingerprint() {
        RuleRegistry asPlace = RuleRegistry.builder()
                .placeKind(new PlaceKindDef(new RuleId("camp"), "Camp")).build();
        RuleRegistry asTask = RuleRegistry.builder()
                .taskKind(new TaskKindDef(new RuleId("camp"), "Camp", 0L, 0L, 1)).build();
        assertNotEquals(asPlace.fingerprint(), asTask.fingerprint());
    }

    @Test
    void fingerprintIsSha256PrefixOfCatalogBytesExcludingRulesVersion()
            throws NoSuchAlgorithmException {
        RuleRegistry rules = RuleRegistry.minimalDefault();
        String input = JsonCodec.write(RulesJson.catalog(rules));
        assertFalse(input.contains("rulesVersion"), input);
        assertFalse(input.contains("rulesFingerprint"), input);

        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8));
        long expected = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        assertEquals(expected, rules.fingerprint());
        assertEquals(expected, RulesFingerprint.of(rules));
    }

    @Test
    void toHexIsZeroPaddedLowercaseAndTwosComplement() {
        assertEquals("0x0000000000000000", RulesFingerprint.toHex(0L));
        assertEquals("0x0000000000000001", RulesFingerprint.toHex(1L));
        assertEquals("0x00000000000000ab", RulesFingerprint.toHex(0xABL));
        assertEquals("0xffffffffffffffff", RulesFingerprint.toHex(-1L));
        assertEquals("0x8000000000000000", RulesFingerprint.toHex(Long.MIN_VALUE));
        assertEquals("0x7fffffffffffffff", RulesFingerprint.toHex(Long.MAX_VALUE));
        assertEquals("0xdeadbeefcafebabe", RulesFingerprint.toHex(0xDEADBEEFCAFEBABEL));
    }

    @Test
    void parseHexRoundTrips() {
        for (long value : new long[] {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE,
                0xDEADBEEFCAFEBABEL, MINIMAL_DEFAULT_FINGERPRINT}) {
            assertEquals(value, RulesFingerprint.parseHex(RulesFingerprint.toHex(value)));
        }
    }

    @Test
    void parseHexIsStrict() {
        for (String bad : List.of("", "0x", "26679a32edcffaaf", "0x26679a32edcffaa",
                "0x26679a32edcffaaf0", "0X26679a32edcffaaf", "0x26679A32EDCFFAAF",
                "0x26679a32edcffaag", " 0x26679a32edcffaaf", "0x26679a32edcffaaf ",
                "0x-6679a32edcffaaf", "0x+6679a32edcffaaf", "1x26679a32edcffaaf")) {
            assertThrows(IllegalArgumentException.class, () -> RulesFingerprint.parseHex(bad),
                    () -> "accepted \"" + bad + "\"");
        }
        assertThrows(NullPointerException.class, () -> RulesFingerprint.parseHex(null));
    }
}
