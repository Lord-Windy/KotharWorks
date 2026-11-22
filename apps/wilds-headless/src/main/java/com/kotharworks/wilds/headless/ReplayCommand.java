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
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code replay --seed <long|0xHex> --wal <file> [--to-ms <ms>] [--hash]}: streams a
 * {@code .jsonl} write-ahead log deterministically against a fresh engine over
 * {@code --seed}. An optional leading {@link WalHeader} line must carry the running
 * engine's version triple (else exit code {@code 2}) and match {@code --seed} (else exit
 * code {@code 3}). Every body record advances the clock to its {@code admittedMs},
 * applies its command (which must be admissible — a WAL only records admitted entries),
 * and — when the record pins a {@code worldHash} — verifies the live hash inline
 * bit-for-bit. {@code --to-ms} stops before the first record admitted beyond that time;
 * {@code --hash} prints the final {@code worldHash=0x<16-hex>} line on success.
 */
final class ReplayCommand {

    private ReplayCommand() {
    }

    static int execute(String[] rawArgs, PrintWriter out, PrintWriter err) {
        String seedRaw = null;
        String walFile = null;
        String toMsRaw = null;
        boolean printHash = false;
        try {
            List<String> args = HeadlessMain.expandEqualsFlags(rawArgs);
            for (int i = 0; i < args.size(); i++) {
                String token = args.get(i);
                switch (token) {
                    case "--seed" ->
                            seedRaw = HeadlessMain.takeFlagValue(args, ++i, "--seed", seedRaw);
                    case "--wal" ->
                            walFile = HeadlessMain.takeFlagValue(args, ++i, "--wal", walFile);
                    case "--to-ms" ->
                            toMsRaw = HeadlessMain.takeFlagValue(args, ++i, "--to-ms", toMsRaw);
                    case "--hash" -> printHash = true;
                    case "--help", "-h" -> {
                        HeadlessMain.printUsage(out);
                        return HeadlessMain.EXIT_OK;
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown replay flag \"" + token + "\"");
                }
            }
            if (seedRaw == null) {
                throw new IllegalArgumentException("missing required flag --seed");
            }
            if (walFile == null) {
                throw new IllegalArgumentException("missing required flag --wal");
            }
            long seed = HeadlessMain.parseSeed(seedRaw);
            Long toMs = toMsRaw == null
                    ? null
                    : HeadlessMain.parseNonNegativeLong(toMsRaw, "--to-ms");
            return replay(seed, walFile, toMs, printHash, out, err);
        } catch (IllegalArgumentException badArgs) {
            err.println("replay: " + badArgs.getMessage());
            return HeadlessMain.EXIT_USAGE;
        }
    }

    private static int replay(long seed, String walFile, Long toMs, boolean printHash,
                              PrintWriter out, PrintWriter err) {
        WildsEngine engine = null;
        long applied = 0;
        long expectedSeq = 1;
        boolean seenFirstNonBlank = false;
        int lineNo = 0;

        try (BufferedReader reader = Files.newBufferedReader(Path.of(walFile),
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
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
                if (!seenFirstNonBlank) {
                    seenFirstNonBlank = true;
                    if (WalHeader.isHeader(obj)) {
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
                        if (header.worldSeed() != seed) {
                            err.println("replay: WAL seed mismatch: header worldSeed="
                                    + HeadlessMain.hex16(header.worldSeed())
                                    + " (" + header.worldSeed() + ") does not match --seed "
                                    + HeadlessMain.hex16(seed) + " (" + seed + ")");
                            return HeadlessMain.EXIT_WAL_INTEGRITY;
                        }
                        continue;
                    }
                } else if (WalHeader.isHeader(obj)) {
                    err.println("replay: line " + lineNo + ": duplicate WAL_HEADER mid-stream");
                    return HeadlessMain.EXIT_WAL_INTEGRITY;
                }

                if (engine == null) {
                    engine = new WildsEngine(new WorldSeed(seed));
                }
                WalRecord record;
                try {
                    record = CommandCodec.decodeWal(obj);
                } catch (IllegalArgumentException malformedRecord) {
                    err.println("replay: line " + lineNo + ": invalid WAL record: "
                            + malformedRecord.getMessage());
                    return HeadlessMain.EXIT_WAL_INTEGRITY;
                }
                if (record.walSeq() != expectedSeq) {
                    err.println("replay: walSeq=" + record.walSeq() + " (line " + lineNo
                            + "): non-ascending WAL sequence (expected " + expectedSeq + ")");
                    return HeadlessMain.EXIT_WAL_INTEGRITY;
                }
                if (toMs != null && record.admittedMs() > toMs) {
                    break;
                }
                String at = "walSeq=" + record.walSeq() + " (line " + lineNo + ")";
                try {
                    engine.advanceTo(record.admittedMs());
                } catch (IllegalStateException clockFailure) {
                    err.println("replay: " + at + ": clock regression: "
                            + clockFailure.getMessage());
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
                Long pinnedHash = record.worldHash();
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
        } catch (IOException | InvalidPathException ioFailure) {
            err.println("replay: cannot read WAL file \"" + walFile + "\": " + ioFailure);
            return HeadlessMain.EXIT_USAGE;
        }

        if (engine == null) {
            engine = new WildsEngine(new WorldSeed(seed));
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
}
