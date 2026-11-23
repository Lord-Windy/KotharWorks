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
package com.kotharworks.wilds.headless;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.QueueFailurePolicy;
import com.kotharworks.wilds.state.TaskSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process acceptance &amp; edge-case tests of the headless CLI (Ticket 05): {@code run}
 * fast-forwards 24 logical hours in under 100ms while writing a valid {@code .jsonl} WAL,
 * {@code replay} reproduces {@code worldHash} bit-for-bit across full and {@code --to-ms}
 * horizons, WAL version/seed/sequence/clock/Phase-1/hash tampering fails with exact exit
 * codes and diagnostics, and {@code inspect} renders the seeded bootstrap topology.
 */
class HeadlessCliTest {

    private static final String SEED_ARG = "0x49524F4E";
    private static final Pattern WORLD_HASH_LINE = Pattern.compile("worldHash=0x([0-9a-f]{16})");
    private static final Pattern EDGE_DURATION = Pattern.compile("\\(\\d+ms\\)");

    @TempDir
    Path tempDir;

    /** One CLI invocation's exit code plus captured stdout/stderr. */
    private record CliResult(int exitCode, String out, String err) {

        /** The 16-hex-digit digest from the {@code worldHash=0x<16-hex>} line. */
        String worldHashHex() {
            Matcher matcher = WORLD_HASH_LINE.matcher(out());
            assertTrue(matcher.find(), () -> "no worldHash line in output:\n" + out());
            return matcher.group(1);
        }
    }

    private static CliResult execute(String... args) {
        StringWriter outSink = new StringWriter();
        StringWriter errSink = new StringWriter();
        int exitCode = HeadlessMain.execute(args,
                new PrintWriter(outSink), new PrintWriter(errSink));
        return new CliResult(exitCode, outSink.toString(), errSink.toString());
    }

    // ------------------------------------------------------------- criterion 1

    @Test
    void runFastForwards24LogicalHoursUnder100msAndWritesValidWal() throws IOException {
        // Warm the engine/codec paths once so the timed run measures steady state.
        execute("run", "--seed", "1", "--owners", "1", "--until-ms", "10000");

        Path wal = tempDir.resolve("run.jsonl");
        long startNanos = System.nanoTime();
        CliResult run = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "86400000", "--out-wal", wal.toString());
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertEquals(HeadlessMain.EXIT_OK, run.exitCode(),
                () -> "run failed, stderr:\n" + run.err());
        assertTrue(elapsedMs < 100, () -> "run took " + elapsedMs + "ms (budget 100ms)");
        assertTrue(run.out().contains("logicalMs=86400000"),
                () -> "run did not land on 86400000ms:\n" + run.out());
        assertEventsEmittedLine(run.out());

        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        assertTrue(lines.size() > 10, () -> "WAL suspiciously short: " + lines.size() + " lines");
        assertTrue(lines.get(0).contains("\"type\":\"WAL_HEADER\""),
                () -> "first WAL line is not a header: " + lines.get(0));

        long previousSeq = 0;
        for (int i = 1; i < lines.size(); i++) {
            int lineNo = i + 1;
            WalRecord record = CommandCodec.decodeWal(lines.get(i));
            assertEquals(previousSeq + 1, record.walSeq(),
                    () -> "walSeq not ascending at line " + lineNo);
            assertNotNull(record.worldHash(),
                    () -> "run must pin a worldHash on every record (line " + lineNo + ")");
            previousSeq = record.walSeq();
        }
        WalRecord finalRecord = CommandCodec.decodeWal(lines.get(lines.size() - 1));
        assertEquals(86400000L, finalRecord.admittedMs(),
                "final WAL record must admit --until-ms");
    }

    // ------------------------------------------------------------- criterion 2

    @Test
    void replayReproducesBitForBitWorldHash() {
        Path wal = tempDir.resolve("run.jsonl");
        CliResult run = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "86400000", "--out-wal", wal.toString());
        assertEquals(HeadlessMain.EXIT_OK, run.exitCode(), () -> run.err());

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString(),
                "--hash");
        assertEquals(HeadlessMain.EXIT_OK, replay.exitCode(),
                () -> "replay failed, stderr:\n" + replay.err());
        assertEquals(run.worldHashHex(), replay.worldHashHex(),
                "replay must reproduce the run's worldHash bit-for-bit");
        assertTrue(replay.out().contains("logicalMs=86400000"),
                () -> "replay did not land on 86400000ms:\n" + replay.out());
    }

    // ------------------------------------------------------------- criterion 3

    @Test
    void tamperedAdmittedMsFailsWithOffendingWalSeq() throws IOException {
        Path wal = writeRunWal();
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        int index = firstClockAdvanceIndex(lines);
        JsonCodec.JsonObj record = (JsonCodec.JsonObj) JsonCodec.parse(lines.get(index));
        long walSeq = jsonInt(record, "walSeq");
        long admittedMs = jsonInt(record, "admittedMs");

        Path tampered = tempDir.resolve("tampered-admit.jsonl");
        writeTamperedWal(tampered, lines, index, replaceField(lines.get(index),
                "admittedMs", admittedMs, admittedMs + 1));

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, replay.exitCode(),
                "tampered admittedMs must exit 3");
        assertTrue(replay.err().contains("walSeq=" + walSeq),
                () -> "stderr must name the offending walSeq=" + walSeq + ":\n" + replay.err());
    }

    @Test
    void clockRegressionOnNullHashWalFailsWithOffendingWalSeq() throws IOException {
        Path wal = tempDir.resolve("clock-regression.jsonl");
        Files.writeString(wal, String.join("\n",
                new WalHeader(1L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION).encode(),
                CommandCodec.encodeWal(new WalRecord.ClockAdvance(1L, 5_000L, null)),
                CommandCodec.encodeWal(new WalRecord.ClockAdvance(2L, 4_999L, null))) + "\n");

        CliResult replay = execute("replay", "--seed", "1", "--wal", wal.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, replay.exitCode(),
                "backward admittedMs must exit 3");
        assertTrue(replay.err().contains("walSeq=2") && replay.err().contains("clock regression"),
                () -> "stderr must report walSeq=2 clock regression:\n" + replay.err());
    }

    @Test
    void inadmissibleCommandInWalFailsWithOffendingWalSeq() throws IOException {
        Path wal = tempDir.resolve("inadmissible-cmd.jsonl");
        WorldCommand duplicateSeat = new WorldCommand.RegisterOwner(1L, "Owner-1", "Scout-1");
        Files.writeString(wal, String.join("\n",
                CommandCodec.encodeWal(new WalRecord.CommandEntry(
                        1L, 0L, 1L, "cmd-1", duplicateSeat, null)),
                CommandCodec.encodeWal(new WalRecord.CommandEntry(
                        2L, 100L, 1L, "cmd-2", duplicateSeat, null))) + "\n");

        CliResult replay = execute("replay", "--seed", "1", "--wal", wal.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, replay.exitCode(),
                "Phase-1 rejected WAL command must exit 3");
        assertTrue(replay.err().contains("walSeq=2")
                        && replay.err().contains("DUPLICATE_SEAT"),
                () -> "stderr must report walSeq=2 DUPLICATE_SEAT:\n" + replay.err());
    }

    @Test
    void tamperedInlineWorldHashFailsWithOffendingWalSeq() throws IOException {
        Path wal = writeRunWal();
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        int index = firstClockAdvanceIndex(lines);
        JsonCodec.JsonObj record = (JsonCodec.JsonObj) JsonCodec.parse(lines.get(index));
        long walSeq = jsonInt(record, "walSeq");
        long worldHash = jsonInt(record, "worldHash");

        Path tampered = tempDir.resolve("tampered-hash.jsonl");
        writeTamperedWal(tampered, lines, index, replaceField(lines.get(index),
                "worldHash", worldHash, worldHash ^ 1L));

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString(),
                "--hash");
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, replay.exitCode(),
                "tampered worldHash must exit 3");
        assertTrue(replay.err().contains("walSeq=" + walSeq),
                () -> "stderr must name the offending walSeq=" + walSeq + ":\n" + replay.err());
    }

    // ------------------------------------------------------------- criterion 4

    @Test
    void mismatchedHeaderRulesVersionReturnsExitCode2() throws IOException {
        Path wal = writeRunWal();
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        Path tampered = tempDir.resolve("bad-rules-version.jsonl");
        writeTamperedWal(tampered, lines, 0,
                lines.get(0).replace("\"rulesVersion\":1", "\"rulesVersion\":999"));

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString());
        assertEquals(HeadlessMain.EXIT_VERSION_MISMATCH, replay.exitCode(),
                () -> "rulesVersion mismatch must exit 2, stderr:\n" + replay.err());
        assertTrue(replay.err().toLowerCase().contains("version"),
                () -> "stderr must carry a version-mismatch diagnostic:\n" + replay.err());
    }

    @Test
    void mismatchedHeaderEngineVersionReturnsExitCode2() throws IOException {
        Path wal = writeRunWal();
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        Path tampered = tempDir.resolve("bad-engine-version.jsonl");
        writeTamperedWal(tampered, lines, 0,
                lines.get(0).replace("\"engineVersion\":1", "\"engineVersion\":99"));

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString());
        assertEquals(HeadlessMain.EXIT_VERSION_MISMATCH, replay.exitCode(),
                () -> "engineVersion mismatch must exit 2, stderr:\n" + replay.err());
    }

    @Test
    void mismatchedHeaderWorldSeedFailsEvenWhenRecordsHaveNullHashes() throws IOException {
        Path wal = tempDir.resolve("server-null-hash.jsonl");
        Files.writeString(wal, String.join("\n",
                new WalHeader(1L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION).encode(),
                CommandCodec.encodeWal(new WalRecord.CommandEntry(1L, 0L, 1L, "cmd-1",
                        new WorldCommand.RegisterOwner(1L, "Owner-1", "Scout-1"), null)),
                CommandCodec.encodeWal(new WalRecord.ClockAdvance(2L, 10_000L, null))) + "\n");

        CliResult wrongSeed = execute("replay", "--seed", "2", "--wal", wal.toString(), "--hash");
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, wrongSeed.exitCode(),
                () -> "header worldSeed mismatch must exit 3, stderr:\n" + wrongSeed.err());
        assertTrue(wrongSeed.err().contains("seed mismatch"),
                () -> "stderr must report seed mismatch:\n" + wrongSeed.err());
    }

    @Test
    void runWritesRulesFingerprintInWalHeader() throws IOException {
        Path wal = writeRunWal();
        String first = Files.readAllLines(wal, StandardCharsets.UTF_8).get(0);
        WalHeader header = WalHeader.decode(first);
        assertEquals(RuleRegistry.minimalDefault().fingerprint(), header.rulesFingerprint());
    }

    @Test
    void tamperedHeaderFingerprintExitsWithVersionMismatchAndDiagnostic() throws IOException {
        Path wal = writeRunWal();
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        WalHeader good = WalHeader.decode(lines.get(0));
        Path tampered = tempDir.resolve("bad-fingerprint.jsonl");
        writeTamperedWal(tampered, lines, 0, new WalHeader(good.worldSeed(),
                good.rulesVersion(), good.engineVersion(),
                good.rulesFingerprint() ^ 1L).encode());

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString());
        assertEquals(HeadlessMain.EXIT_VERSION_MISMATCH, replay.exitCode(),
                () -> "fingerprint mismatch must exit 2, stderr:\n" + replay.err());
        assertTrue(replay.err().contains("fingerprint"),
                () -> "stderr must carry a fingerprint diagnostic:\n" + replay.err());
    }

    @Test
    void legacyHeaderWithoutFingerprintAndCorrectFingerprintBothReplay() throws IOException {
        Path wal = writeRunWal();
        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        WalHeader good = WalHeader.decode(lines.get(0));
        assertTrue(lines.get(0).contains("rulesFingerprint"));

        Path legacy = tempDir.resolve("legacy-header.jsonl");
        String legacyLine = new WalHeader(good.worldSeed(), good.rulesVersion(),
                good.engineVersion()).encode();
        assertFalse(legacyLine.contains("rulesFingerprint"));
        writeTamperedWal(legacy, lines, 0, legacyLine);
        CliResult legacyReplay = execute("replay", "--seed", SEED_ARG, "--wal",
                legacy.toString());
        assertEquals(HeadlessMain.EXIT_OK, legacyReplay.exitCode(),
                () -> "legacy header must replay, stderr:\n" + legacyReplay.err());

        CliResult current = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString());
        assertEquals(HeadlessMain.EXIT_OK, current.exitCode(),
                () -> "correct fingerprint must replay, stderr:\n" + current.err());
    }

    // ------------------------------------------------------------- criterion 5

    @Test
    void inspectPrintsBootstrapPlacesEdgesAndDescriptions() {
        CliResult inspect = execute("inspect", "--seed", SEED_ARG);
        assertEquals(HeadlessMain.EXIT_OK, inspect.exitCode(),
                () -> "inspect failed, stderr:\n" + inspect.err());
        String out = inspect.out();
        for (String expected : new String[] {
                "plc:0:1", "plc:1:1", "plc:2:1",
                "Origin Clearing", "Ridge Trail", "Old Boundary Stone",
                "(camp)", "(trail)", "(landmark)",
                "glance:", "look:", "examine:"}) {
            assertTrue(out.contains(expected), () -> "inspect output missing \"" + expected
                    + "\":\n" + out);
        }
        assertTrue(out.indexOf("place [plc:0:1]") < out.indexOf("place [plc:1:1]")
                && out.indexOf("place [plc:1:1]") < out.indexOf("place [plc:2:1]"),
                "places must appear in ascending PlaceId order");
        Matcher durations = EDGE_DURATION.matcher(out);
        int edgeMentions = 0;
        while (durations.find()) {
            edgeMentions++;
        }
        int mentionCount = edgeMentions;
        assertTrue(mentionCount >= 4, () -> "expected >= 4 edge travel-time mentions, got "
                + mentionCount + ":\n" + out);
        assertTrue(out.trim().endsWith("places=3 edges=4"),
                () -> "inspect must summarize 3 places and 4 edges:\n" + out);
    }

    // ------------------------------------------------------------- extra guards

    @Test
    void replayWithoutHashFlagStillVerifiesInlineHashes() throws IOException {
        Path wal = writeRunWal();
        CliResult clean = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString());
        assertEquals(HeadlessMain.EXIT_OK, clean.exitCode(), () -> clean.err());
        assertFalse(clean.out().contains("worldHash="),
                "without --hash the final worldHash line must be omitted");

        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        int index = firstClockAdvanceIndex(lines);
        JsonCodec.JsonObj record = (JsonCodec.JsonObj) JsonCodec.parse(lines.get(index));
        Path tampered = tempDir.resolve("tampered-nohash.jsonl");
        writeTamperedWal(tampered, lines, index, replaceField(lines.get(index), "worldHash",
                jsonInt(record, "worldHash"), jsonInt(record, "worldHash") ^ 1L));
        CliResult corrupted = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, corrupted.exitCode(),
                "inline hash verification must run even without --hash");
    }

    @Test
    void unheaderedAndNullHashWalReplaysToIdenticalWorldHash() throws IOException {
        Path wal = tempDir.resolve("full.jsonl");
        String expectedHash = execute("run", "--seed", SEED_ARG, "--owners", "3",
                "--until-ms", "60000", "--out-wal", wal.toString()).worldHashHex();

        List<String> rawLines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        List<String> stripped = new ArrayList<>(rawLines.size());
        for (int i = 1; i < rawLines.size(); i++) {
            WalRecord original = CommandCodec.decodeWal(rawLines.get(i));
            WalRecord withoutHash = switch (original) {
                case WalRecord.ClockAdvance a ->
                        new WalRecord.ClockAdvance(a.walSeq(), a.admittedMs(), null);
                case WalRecord.CommandEntry e -> new WalRecord.CommandEntry(
                        e.walSeq(), e.admittedMs(), e.seatId(), e.cmdId(), e.command(), null);
            };
            stripped.add(CommandCodec.encodeWal(withoutHash));
        }
        Path unheadered = tempDir.resolve("unheadered-null-hash.jsonl");
        Files.writeString(unheadered, "\n" + String.join("\n", stripped) + "\n");

        CliResult replay = execute("replay", "--seed", SEED_ARG,
                "--wal", unheadered.toString(), "--hash");
        assertEquals(HeadlessMain.EXIT_OK, replay.exitCode(), () -> replay.err());
        assertEquals(expectedHash, replay.worldHashHex(),
                "unheadered null-hash WAL must replay to identical worldHash");
    }

    @Test
    void corruptedJsonSequenceGapsAndDuplicateHeadersExitWalIntegrity() throws IOException {
        Path badJson = tempDir.resolve("bad-json.jsonl");
        Files.writeString(badJson, "{not-json}\n");
        CliResult r1 = execute("replay", "--seed", "1", "--wal", badJson.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, r1.exitCode());
        assertTrue(r1.err().contains("line 1"));

        Path arrayJson = tempDir.resolve("array.jsonl");
        Files.writeString(arrayJson, "[1,2,3]\n");
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY,
                execute("replay", "--seed", "1", "--wal", arrayJson.toString()).exitCode());

        Path badHeader = tempDir.resolve("bad-header.jsonl");
        Files.writeString(badHeader, "{\"type\":\"WAL_HEADER\",\"worldSeed\":\"oops\"}\n");
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY,
                execute("replay", "--seed", "1", "--wal", badHeader.toString()).exitCode());

        String headerLine = new WalHeader(1L, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION).encode();
        Path dupHeader = tempDir.resolve("dup-header.jsonl");
        Files.writeString(dupHeader, headerLine + "\n" + headerLine + "\n");
        CliResult rDup = execute("replay", "--seed", "1", "--wal", dupHeader.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, rDup.exitCode());
        assertTrue(rDup.err().contains("duplicate WAL_HEADER"));

        Path seqGap = tempDir.resolve("seq-gap.jsonl");
        Files.writeString(seqGap, headerLine + "\n"
                + CommandCodec.encodeWal(new WalRecord.ClockAdvance(2L, 100L, null)) + "\n");
        CliResult rGap = execute("replay", "--seed", "1", "--wal", seqGap.toString());
        assertEquals(HeadlessMain.EXIT_WAL_INTEGRITY, rGap.exitCode());
        assertTrue(rGap.err().contains("walSeq=2"));
    }

    @Test
    void replayToMsHorizonMatchesPartialRunsAndSameTimestampBoundaries() throws IOException {
        Path wal = writeRunWal();

        // Horizon 0ms: applies all 8 commands admitted at t=0, stops before t=5542.
        CliResult runAt0 = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "0");
        CliResult replayAt0 = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString(),
                "--to-ms", "0", "--hash");
        assertEquals(HeadlessMain.EXIT_OK, replayAt0.exitCode(), () -> replayAt0.err());
        assertTrue(replayAt0.out().contains("replayed=8 logicalMs=0"), () -> replayAt0.out());
        assertEquals(runAt0.worldHashHex(), replayAt0.worldHashHex());

        // Mid-flight horizon 1000ms (between t=0 and first due event at 5542ms).
        CliResult runAt1000 = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "1000");
        CliResult replayAt1000 = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString(),
                "--to-ms", "1000", "--hash");
        assertEquals(HeadlessMain.EXIT_OK, replayAt1000.exitCode(), () -> replayAt1000.err());
        assertTrue(replayAt1000.out().contains("logicalMs=1000"), () -> replayAt1000.out());
        assertEquals(runAt1000.worldHashHex(), replayAt1000.worldHashHex());

        // Exact due-event + multi-record shared timestamp at t=2000ms, followed by t=2001ms.
        WildsEngine expected = new WildsEngine(new WorldSeed(7L));
        OwnerId own0 = new OwnerId(0, 1);
        ActorId act0 = new ActorId(0, 1);
        WorldCommand register = new WorldCommand.RegisterOwner(1L, "Owner-1", "Scout-1");
        WorldCommand wait2s = new WorldCommand.EnqueueTasks(own0, act0, null,
                List.of(new TaskSpec.Wait(2_000L, 1)));
        WorldCommand setHalt = new WorldCommand.SetFailurePolicy(own0, act0, null,
                QueueFailurePolicy.HALT_ON_FAIL);
        WorldCommand laterWait = new WorldCommand.EnqueueTasks(own0, act0, null,
                List.of(new TaskSpec.Wait(1_000L, 1)));

        expected.apply(register);
        expected.apply(wait2s);
        expected.advanceTo(2_000L); // completes wait2s at exact boundary t=2000ms
        expected.apply(setHalt);    // second WAL entry sharing admittedMs=2000ms

        Path sharedTsWal = tempDir.resolve("shared-ts.jsonl");
        Files.writeString(sharedTsWal, String.join("\n",
                new WalHeader(7L, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION).encode(),
                CommandCodec.encodeWal(new WalRecord.CommandEntry(
                        1L, 0L, 1L, "c1", register, null)),
                CommandCodec.encodeWal(new WalRecord.CommandEntry(
                        2L, 0L, 1L, "c2", wait2s, null)),
                CommandCodec.encodeWal(new WalRecord.ClockAdvance(3L, 2_000L, null)),
                CommandCodec.encodeWal(new WalRecord.CommandEntry(
                        4L, 2_000L, 1L, "c4", setHalt, null)),
                CommandCodec.encodeWal(new WalRecord.CommandEntry(
                        5L, 2_001L, 1L, "c5", laterWait, null))) + "\n");

        CliResult bounded = execute("replay", "--seed", "7", "--wal", sharedTsWal.toString(),
                "--to-ms", "2000", "--hash");
        assertEquals(HeadlessMain.EXIT_OK, bounded.exitCode(), () -> bounded.err());
        assertTrue(bounded.out().contains("replayed=4 logicalMs=2000"), () -> bounded.out());
        assertEquals(HeadlessMain.hex16(expected.worldHash()), "0x" + bounded.worldHashHex());
    }

    @Test
    void unsignedHexAndSignedDecimalSeedsAgreeAcrossFull64BitRange() {
        CliResult hex = execute("run", "--seed", SEED_ARG, "--owners", "2",
                "--until-ms", "60000");
        CliResult decimal = execute("run", "--seed", "1230131022", "--owners", "2",
                "--until-ms", "60000");
        assertEquals(hex.worldHashHex(), decimal.worldHashHex(),
                "0x49524F4E and its signed decimal form must seed identical worlds");

        // Bit 63 set: 0x8000000000000000 == Long.MIN_VALUE, plus WAL header round-trip.
        Path minWal = tempDir.resolve("min-seed.jsonl");
        CliResult minHex = execute("run", "--seed", "0x8000000000000000", "--owners", "1",
                "--until-ms", "20000", "--out-wal", minWal.toString());
        CliResult minDec = execute("run", "--seed", Long.toString(Long.MIN_VALUE),
                "--owners", "1", "--until-ms", "20000");
        CliResult minReplay = execute("replay", "--seed", "0X8000000000000000",
                "--wal", minWal.toString(), "--hash");
        assertEquals(HeadlessMain.EXIT_OK, minHex.exitCode(), () -> minHex.err());
        assertEquals(minHex.worldHashHex(), minDec.worldHashHex());
        assertEquals(HeadlessMain.EXIT_OK, minReplay.exitCode(), () -> minReplay.err());
        assertEquals(minHex.worldHashHex(), minReplay.worldHashHex());

        // All bits set: 0xFFFFFFFFFFFFFFFF == -1L.
        CliResult allOnesHex = execute("run", "--seed", "0xFFFFFFFFFFFFFFFF", "--owners", "1",
                "--until-ms", "20000");
        CliResult minusOne = execute("run", "--seed", "-1", "--owners", "1",
                "--until-ms", "20000");
        assertEquals(HeadlessMain.EXIT_OK, allOnesHex.exitCode(), () -> allOnesHex.err());
        assertEquals(allOnesHex.worldHashHex(), minusOne.worldHashHex());

        // Malformed hex forms rejected cleanly.
        for (String badSeed : new String[] {
                "0x", "0X", "0x+1", "0x-1", "0xZZZ", "0x10000000000000000"}) {
            assertEquals(HeadlessMain.EXIT_USAGE,
                    execute("run", "--seed", badSeed).exitCode(),
                    "bad seed must exit 1: " + badSeed);
        }
    }

    @Test
    void equalsFlagsMissingValuesDuplicateFlagsAndBufferedWritersBehaveCleanly() {
        // --flag=value syntax across all 3 subcommands.
        Path wal = tempDir.resolve("eq.jsonl");
        CliResult eqRun = execute("run", "--seed=0x49524F4E", "--owners=1",
                "--until-ms=10000", "--out-wal=" + wal);
        assertEquals(HeadlessMain.EXIT_OK, eqRun.exitCode(), () -> eqRun.err());
        CliResult eqReplay = execute("replay", "--seed=0x49524F4E", "--wal=" + wal,
                "--to-ms=10000", "--hash");
        assertEquals(HeadlessMain.EXIT_OK, eqReplay.exitCode(), () -> eqReplay.err());
        assertEquals(eqRun.worldHashHex(), eqReplay.worldHashHex());
        assertEquals(HeadlessMain.EXIT_OK,
                execute("inspect", "--seed=0x49524F4E").exitCode());

        // Help flags exit 0.
        assertEquals(HeadlessMain.EXIT_OK, execute("--help").exitCode());
        assertEquals(HeadlessMain.EXIT_OK, execute("run", "--help").exitCode());

        // Usage failures & guard against "--out-wal --until-ms" creating a "--until-ms" file.
        assertEquals(HeadlessMain.EXIT_USAGE, execute().exitCode(), "no args must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("frobnicate", "--seed", "1").exitCode(), "unknown subcommand must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("run").exitCode(), "run without --seed must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("replay", "--seed", "1").exitCode(), "replay without --wal must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("run", "--seed", "1", "--seed", "2").exitCode(),
                "duplicate --seed must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("run", "--seed", "1", "--out-wal", "--until-ms").exitCode(),
                "missing --out-wal value before --until-ms must exit 1");
        assertFalse(Files.exists(Path.of("--until-ms")),
                "must never create a literal --until-ms file");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("replay", "--seed", "1", "--wal", "--hash").exitCode(),
                "missing --wal value before --hash must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("inspect", "--seed", SEED_ARG, "--wat").exitCode(),
                "unknown inspect flag must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("replay", "--seed", SEED_ARG,
                        "--wal", tempDir.resolve("does-not-exist.jsonl").toString()).exitCode(),
                "missing WAL file must exit 1");

        // Buffered PrintWriter(OutputStream) receives flushed output without caller flush.
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        ByteArrayOutputStream rawErr = new ByteArrayOutputStream();
        int code = HeadlessMain.execute(new String[] {"inspect", "--seed", "1"},
                new PrintWriter(rawOut), new PrintWriter(rawErr));
        assertEquals(HeadlessMain.EXIT_OK, code);
        assertTrue(rawOut.toString(StandardCharsets.UTF_8).contains("places=3 edges=4"));
        assertFalse(HeadlessMain.runningInsideExecJava(),
                "JUnit runner must not be misclassified as exec:java");
    }

    // ------------------------------------------------------------- helpers

    private Path writeRunWal() {
        Path wal = tempDir.resolve("run.jsonl");
        CliResult run = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "86400000", "--out-wal", wal.toString());
        assertEquals(HeadlessMain.EXIT_OK, run.exitCode(),
                () -> "run failed, stderr:\n" + run.err());
        return wal;
    }

    private static void assertEventsEmittedLine(String out) {
        Matcher matcher = Pattern.compile("eventsEmitted=(\\d+)").matcher(out);
        assertTrue(matcher.find(), () -> "no eventsEmitted line:\n" + out);
        assertTrue(Long.parseLong(matcher.group(1)) > 0, "run must emit events");
    }

    private static int firstClockAdvanceIndex(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("\"kind\":\"clockAdvance\"")) {
                return i;
            }
        }
        throw new AssertionError("no clockAdvance record in WAL");
    }

    private static long jsonInt(JsonCodec.JsonObj obj, String field) {
        assertTrue(obj.getOrNull(field) instanceof JsonCodec.JsonInt,
                () -> "WAL record lacks integer field \"" + field + "\": " + obj);
        return ((JsonCodec.JsonInt) obj.getOrNull(field)).value();
    }

    /** Rewrites one canonical JSON field, asserting the needle occurs exactly once. */
    private static String replaceField(String line, String field, long oldValue, long newValue) {
        String needle = "\"" + field + "\":" + oldValue;
        assertEquals(1, countOccurrences(line, needle),
                () -> "field needle \"" + needle + "\" not unique in: " + line);
        return line.replace(needle, "\"" + field + "\":" + newValue);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static void writeTamperedWal(Path target, List<String> original, int index,
                                         String replacementLine) throws IOException {
        List<String> lines = new ArrayList<>(original);
        lines.set(index, replacementLine);
        Files.write(target, (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
