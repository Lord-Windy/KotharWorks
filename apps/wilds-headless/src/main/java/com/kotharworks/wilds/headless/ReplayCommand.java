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
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.JsonCodec;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code replay --seed <long|0xHex> --wal <file> [--to-ms <ms>] [--hash]}: re-drives a
 * {@code .jsonl} write-ahead log deterministically against a fresh engine over
 * {@code --seed}. An optional leading {@link WalHeader} line must carry the running
 * engine's version triple or the replay fails fast with exit code {@code 2}. Every body
 * record advances the clock to its {@code admittedMs}, applies its command (which must
 * be admissible — a WAL only records admitted entries), and — when the record pins a
 * {@code worldHash} — verifies the live hash inline bit-for-bit. {@code --to-ms} stops
 * before the first record admitted beyond that time; {@code --hash} prints the final
 * {@code worldHash=0x<16-hex>} line on success.
 */
final class ReplayCommand {

    private ReplayCommand() {
    }

    static int execute(String[] args, PrintWriter out, PrintWriter err) {
        String seedRaw = null;
        String walFile = null;
        String toMsRaw = null;
        boolean printHash = false;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--seed" -> seedRaw = takeValue(args, ++i, "--seed");
                    case "--wal" -> walFile = takeValue(args, ++i, "--wal");
                    case "--to-ms" -> toMsRaw = takeValue(args, ++i, "--to-ms");
                    case "--hash" -> printHash = true;
                    default -> throw new IllegalArgumentException(
                            "unknown replay flag \"" + args[i] + "\"");
                }
            }
            if (seedRaw == null) {
                throw new IllegalArgumentException("missing required flag --seed");
            }
            if (walFile == null) {
                throw new IllegalArgumentException("missing required flag --wal");
            }
            long seed = HeadlessMain.parseSeed(seedRaw);
            Long toMs = toMsRaw == null ? null : parseNonNegativeLong(toMsRaw);
            return replay(seed, walFile, toMs, printHash, out, err);
        } catch (IllegalArgumentException badArgs) {
            err.println("replay: " + badArgs.getMessage());
            return HeadlessMain.EXIT_USAGE;
        }
    }

    private static int replay(long seed, String walFile, Long toMs, boolean printHash,
                              PrintWriter out, PrintWriter err) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(walFile), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException ioFailure) {
            err.println("replay: cannot read WAL file \"" + walFile + "\": " + ioFailure);
            return HeadlessMain.EXIT_USAGE;
        }

        // Optional leading header line: fail fast on a version-triple mismatch.
        int headerLineNo = 0;
        int scan = 0;
        while (scan < lines.size() && lines.get(scan).isBlank()) {
            scan++;
        }
        if (scan < lines.size()) {
            int lineNo = scan + 1;
            JsonCodec.JsonValue parsed;
            try {
                parsed = JsonCodec.parse(lines.get(scan));
            } catch (IllegalArgumentException malformed) {
                err.println("replay: line " + lineNo + ": invalid WAL line: " + malformed.getMessage());
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            if (parsed instanceof JsonCodec.JsonObj obj && WalHeader.isHeader(obj)) {
                WalHeader header;
                try {
                    header = WalHeader.decode(obj);
                } catch (IllegalArgumentException malformedHeader) {
                    err.println("replay: line " + lineNo + ": invalid WAL header: "
                            + malformedHeader.getMessage());
                    return HeadlessMain.EXIT_WAL_INTEGRITY;
                }
                if (header.rulesVersion() != RuleRegistry.WILDS_RULES_VERSION
                        || header.engineVersion() != WildsEngine.API_VERSION) {
                    err.println("replay: WAL version mismatch: header " + header
                            + " but this rig runs rulesVersion="
                            + RuleRegistry.WILDS_RULES_VERSION + " engineVersion="
                            + WildsEngine.API_VERSION);
                    return HeadlessMain.EXIT_VERSION_MISMATCH;
                }
                headerLineNo = lineNo;
            }
        }

        WildsEngine engine = new WildsEngine(new WorldSeed(seed));
        long applied = 0;
        long expectedSeq = 1;
        for (int i = 0; i < lines.size(); i++) {
            int lineNo = i + 1;
            String line = lines.get(i);
            if (line.isBlank() || lineNo == headerLineNo) {
                continue;
            }
            JsonCodec.JsonValue parsed;
            try {
                parsed = JsonCodec.parse(line);
            } catch (IllegalArgumentException malformed) {
                err.println("replay: line " + lineNo + ": invalid WAL line: "
                        + malformed.getMessage());
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            if (!(parsed instanceof JsonCodec.JsonObj obj)) {
                err.println("replay: line " + lineNo + ": WAL record must be a JSON object");
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            if (WalHeader.isHeader(obj)) {
                err.println("replay: line " + lineNo + ": duplicate WAL_HEADER mid-stream");
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            WalRecord record;
            try {
                record = CommandCodec.decodeWal(line);
            } catch (IllegalArgumentException malformedRecord) {
                err.println("replay: line " + lineNo + ": invalid WAL record: "
                        + malformedRecord.getMessage());
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            if (walSeq(record) != expectedSeq) {
                err.println("replay: walSeq=" + walSeq(record) + " (line " + lineNo
                        + "): non-ascending WAL sequence (expected " + expectedSeq + ")");
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            if (toMs != null && admittedMs(record) > toMs) {
                break;
            }
            String at = "walSeq=" + walSeq(record) + " (line " + lineNo + ")";
            try {
                engine.advanceTo(admittedMs(record));
            } catch (IllegalStateException clockFailure) {
                err.println("replay: " + at + ": clock regression: " + clockFailure.getMessage());
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            if (record instanceof WalRecord.CommandEntry entry) {
                Phase1Result result = engine.apply(entry.command());
                if (result instanceof Phase1Result.Rejected rejected) {
                    err.println("replay: " + at + ": inadmissible command "
                            + rejected.errorCode() + ": " + rejected.detail());
                    return HeadlessMain.EXIT_WAL_INTEGRITY;
                }
            }
            Long pinnedHash = worldHash(record);
            if (pinnedHash != null && pinnedHash.longValue() != engine.worldHash()) {
                err.println("replay: " + at + ": worldHash mismatch: wal="
                        + HeadlessMain.hex16(pinnedHash) + " engine="
                        + HeadlessMain.hex16(engine.worldHash()));
                return HeadlessMain.EXIT_WAL_INTEGRITY;
            }
            engine.drainEvents();
            applied++;
            expectedSeq++;
        }
        if (toMs != null && engine.nowMs() < toMs) {
            engine.advanceTo(toMs);
        }
        engine.drainEvents();
        out.println("replayed=" + applied + " logicalMs=" + engine.nowMs());
        if (printHash) {
            out.println("worldHash=" + HeadlessMain.hex16(engine.worldHash()));
        }
        return HeadlessMain.EXIT_OK;
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

    /** The pinned post-entry digest, or null when the record pins none. */
    private static Long worldHash(WalRecord record) {
        return switch (record) {
            case WalRecord.ClockAdvance advance -> advance.worldHash();
            case WalRecord.CommandEntry entry -> entry.worldHash();
        };
    }

    private static long parseNonNegativeLong(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value < 0) {
                throw new IllegalArgumentException("--to-ms must be >= 0: " + raw);
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    "--to-ms must be a signed decimal long: \"" + raw + "\"");
        }
    }

    private static String takeValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }
}
