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

import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.WalRecord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process acceptance tests of the headless CLI (Ticket 05): {@code run} must
 * fast-forward 24 logical hours in under 100ms while writing a valid {@code .jsonl}
 * WAL, {@code replay} must reproduce the run's {@code worldHash} bit-for-bit, tampering
 * with one {@code admittedMs} or inline {@code worldHash} must fail with the offending
 * {@code walSeq} in stderr, a mismatched WAL header version must exit with code 2, and
 * {@code inspect} must print the bootstrap topology and zoom descriptions.
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

        assertEquals(0, run.exitCode(), () -> "run failed, stderr:\n" + run.err());
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
            assertTrue(walSeq(record) == previousSeq + 1,
                    () -> "walSeq not ascending at line " + lineNo);
            assertTrue(worldHash(record) != null,
                    () -> "run must pin a worldHash on every record (line " + lineNo + ")");
            previousSeq = walSeq(record);
        }
        WalRecord finalRecord = CommandCodec.decodeWal(lines.get(lines.size() - 1));
        assertEquals(86400000L, admittedMs(finalRecord),
                "final WAL record must admit --until-ms");
    }

    // ------------------------------------------------------------- criterion 2

    @Test
    void replayReproducesBitForBitWorldHash() throws IOException {
        Path wal = writeRunWal();
        String runHash = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "86400000", "--out-wal", wal.toString()).worldHashHex();

        CliResult replay = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString(),
                "--hash");
        assertEquals(0, replay.exitCode(), () -> "replay failed, stderr:\n" + replay.err());
        assertEquals(runHash, replay.worldHashHex(),
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
        assertNotEquals(0, replay.exitCode(), "tampered admittedMs must fail replay");
        assertTrue(replay.err().contains("walSeq=" + walSeq),
                () -> "stderr must name the offending walSeq=" + walSeq + ":\n" + replay.err());
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
        assertNotEquals(0, replay.exitCode(), "tampered worldHash must fail replay");
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
        assertEquals(2, replay.exitCode(),
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
        assertEquals(2, replay.exitCode(),
                () -> "engineVersion mismatch must exit 2, stderr:\n" + replay.err());
    }

    // ------------------------------------------------------------- criterion 5

    @Test
    void inspectPrintsBootstrapPlacesEdgesAndDescriptions() {
        CliResult inspect = execute("inspect", "--seed", SEED_ARG);
        assertEquals(0, inspect.exitCode(), () -> "inspect failed, stderr:\n" + inspect.err());
        String out = inspect.out();
        for (String expected : new String[] {
                "plc:0:1", "plc:1:1", "plc:2:1",
                "Origin Clearing", "Ridge Trail", "Old Boundary Stone",
                "(camp)", "(trail)", "(landmark)",
                "glance:", "look:", "examine:"}) {
            assertTrue(out.contains(expected), () -> "inspect output missing \"" + expected
                    + "\":\n" + out);
        }
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
        assertEquals(0, clean.exitCode(), () -> clean.err());
        assertFalse(clean.out().contains("worldHash="),
                "without --hash the final worldHash line must be omitted");

        List<String> lines = Files.readAllLines(wal, StandardCharsets.UTF_8);
        int index = firstClockAdvanceIndex(lines);
        JsonCodec.JsonObj record = (JsonCodec.JsonObj) JsonCodec.parse(lines.get(index));
        Path tampered = tempDir.resolve("tampered-nohash.jsonl");
        writeTamperedWal(tampered, lines, index, replaceField(lines.get(index), "worldHash",
                jsonInt(record, "worldHash"), jsonInt(record, "worldHash") ^ 1L));
        CliResult corrupted = execute("replay", "--seed", SEED_ARG, "--wal", tampered.toString());
        assertNotEquals(0, corrupted.exitCode(),
                "inline hash verification must run even without --hash");
    }

    @Test
    void replayStopsAtToMsHorizon() throws IOException {
        Path wal = writeRunWal();
        CliResult partial = execute("replay", "--seed", SEED_ARG, "--wal", wal.toString(),
                "--to-ms", "1000", "--hash");
        assertEquals(0, partial.exitCode(), () -> partial.err());
        assertTrue(partial.out().contains("logicalMs=1000"),
                () -> "partial replay must land on the --to-ms horizon:\n" + partial.out());
    }

    @Test
    void hexAndDecimalSeedFormsAgree() {
        CliResult hex = execute("run", "--seed", SEED_ARG, "--owners", "2",
                "--until-ms", "60000");
        CliResult decimal = execute("run", "--seed", "1230131022", "--owners", "2",
                "--until-ms", "60000");
        assertEquals(0, hex.exitCode(), () -> hex.err());
        assertEquals(0, decimal.exitCode(), () -> decimal.err());
        assertEquals(hex.worldHashHex(), decimal.worldHashHex(),
                "0x49524F4E and its signed decimal form must seed identical worlds");
    }

    @Test
    void fullSixtyFourBitUnsignedHexSeedIsAccepted() {
        CliResult run = execute("run", "--seed", "0xFFFFFFFFFFFFFFFF", "--owners", "1",
                "--until-ms", "60000");
        assertEquals(0, run.exitCode(),
                () -> "16-hex-digit seed with bit 63 set must not overflow, stderr:\n" + run.err());
    }

    @Test
    void usageFailuresExitNonZero() {
        assertEquals(HeadlessMain.EXIT_USAGE, execute().exitCode(), "no args must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("frobnicate", "--seed", "1").exitCode(), "unknown subcommand must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("run").exitCode(), "run without --seed must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("replay", "--seed", "1").exitCode(), "replay without --wal must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("run", "--seed", "0xZZZ").exitCode(), "malformed seed must exit 1");
        assertEquals(HeadlessMain.EXIT_USAGE,
                execute("inspect", "--seed", SEED_ARG, "--wat").exitCode(),
                "unknown inspect flag must exit 1");
        CliResult missingFile = execute("replay", "--seed", SEED_ARG,
                "--wal", tempDir.resolve("does-not-exist.jsonl").toString());
        assertEquals(HeadlessMain.EXIT_USAGE, missingFile.exitCode(),
                "missing WAL file must exit 1");
    }

    // ------------------------------------------------------------- helpers

    private Path writeRunWal() throws IOException {
        Path wal = tempDir.resolve("run.jsonl");
        CliResult run = execute("run", "--seed", SEED_ARG, "--owners", "4",
                "--until-ms", "86400000", "--out-wal", wal.toString());
        assertEquals(0, run.exitCode(), () -> "run failed, stderr:\n" + run.err());
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

    private static long walSeq(WalRecord record) {
        return switch (record) {
            case WalRecord.ClockAdvance advance -> advance.walSeq();
            case WalRecord.CommandEntry entry -> entry.walSeq();
        };
    }

    private static long admittedMs(WalRecord record) {
        return switch (record) {
            case WalRecord.ClockAdvance advance -> advance.admittedMs();
            case WalRecord.CommandEntry entry -> entry.admittedMs();
        };
    }

    private static Long worldHash(WalRecord record) {
        return switch (record) {
            case WalRecord.ClockAdvance advance -> advance.worldHash();
            case WalRecord.CommandEntry entry -> entry.worldHash();
        };
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
