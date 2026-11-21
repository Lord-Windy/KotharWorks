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
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.id.PlaceId;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.TaskSpec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code run --seed <long|0xHex> [--owners <n>] [--until-ms <ms>] [--out-wal <file>]}:
 * registers {@code <n>} test owner seats at the home place, queues a sample
 * {@code Travel}/{@code Wait} loop for every actor, fast-forwards the pure engine via
 * {@code advanceToNextEvent()} up to {@code --until-ms} (zero idle cost), optionally
 * writes the exact admission stream as a {@code .jsonl} WAL, and prints the final
 * {@code logicalMs}, {@code eventsEmitted}, and {@code worldHash=0x<16-hex>}.
 */
final class RunCommand {

    /** Default seat count when {@code --owners} is omitted. */
    static final int DEFAULT_OWNERS = 2;

    /** Default fast-forward horizon when {@code --until-ms} is omitted (1 logical hour). */
    static final long DEFAULT_UNTIL_MS = 3_600_000L;

    /** Sample loop destinations: home {@code plc:0:1} <-> ridge {@code plc:1:1}. */
    private static final PlaceId RIDGE_PLACE_ID = new PlaceId(1, 1);

    /** Sample loop wait: {@code 2_000ms} per batch, {@code 2} batches. */
    private static final TaskSpec.Wait SAMPLE_WAIT = new TaskSpec.Wait(2_000L, 2);

    private RunCommand() {
    }

    static int execute(String[] args, PrintWriter out, PrintWriter err) {
        String seedRaw = null;
        String ownersRaw = null;
        String untilMsRaw = null;
        String outWal = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--seed" -> seedRaw = takeValue(args, ++i, "--seed");
                    case "--owners" -> ownersRaw = takeValue(args, ++i, "--owners");
                    case "--until-ms" -> untilMsRaw = takeValue(args, ++i, "--until-ms");
                    case "--out-wal" -> outWal = takeValue(args, ++i, "--out-wal");
                    default -> throw new IllegalArgumentException(
                            "unknown run flag \"" + args[i] + "\"");
                }
            }
            if (seedRaw == null) {
                throw new IllegalArgumentException("missing required flag --seed");
            }
            long seed = HeadlessMain.parseSeed(seedRaw);
            int owners = ownersRaw == null ? DEFAULT_OWNERS : parseOwners(ownersRaw);
            long untilMs = untilMsRaw == null
                    ? DEFAULT_UNTIL_MS
                    : parseNonNegativeLong(untilMsRaw, "--until-ms");
            return run(seed, owners, untilMs, outWal, out, err);
        } catch (IllegalArgumentException badArgs) {
            err.println("run: " + badArgs.getMessage());
            return HeadlessMain.EXIT_USAGE;
        }
    }

    private static int run(long seed, int owners, long untilMs, String outWal,
                           PrintWriter out, PrintWriter err) {
        WildsEngine engine = new WildsEngine(new WorldSeed(seed));
        long eventsEmitted = 0;
        long walSeq = 1;
        List<WalRecord> wal = new ArrayList<>();
        List<OwnerSeat> seats = new ArrayList<>(owners);

        // (1) Register the test owner seats; every admission lands at logical t=0.
        for (int i = 1; i <= owners; i++) {
            WorldCommand.RegisterOwner command =
                    new WorldCommand.RegisterOwner(i, "Owner-" + i, "Scout-" + i);
            requireAccepted(engine, command);
            eventsEmitted += engine.drainEvents().size();
            OwnerId ownerId = engine.view().findOwnerBySeatKey(i).orElseThrow().id();
            ActorId actorId = engine.view().actorsOwnedBy(ownerId).get(0).id();
            seats.add(new OwnerSeat(i, ownerId, actorId));
            wal.add(new WalRecord.CommandEntry(walSeq++, engine.nowMs(), i,
                    "headless-" + (walSeq - 1), command, engine.worldHash()));
        }

        // (2) Queue the sample Travel/Wait loop for every actor, still at t=0.
        for (OwnerSeat seat : seats) {
            WorldCommand.EnqueueTasks command = new WorldCommand.EnqueueTasks(
                    seat.ownerId(), seat.actorId(), null,
                    List.of(new TaskSpec.Travel(RIDGE_PLACE_ID), SAMPLE_WAIT,
                            new TaskSpec.Travel(WildsEngine.HOME_PLACE_ID)));
            requireAccepted(engine, command);
            eventsEmitted += engine.drainEvents().size();
            wal.add(new WalRecord.CommandEntry(walSeq++, engine.nowMs(), seat.seatKey(),
                    "headless-" + (walSeq - 1), command, engine.worldHash()));
        }

        // (3) Fast-forward: one engine step per distinct due timestamp, zero idle cost.
        while (engine.peekNextDueMs().isPresent()
                && engine.peekNextDueMs().getAsLong() <= untilMs) {
            long advancedTo = engine.advanceToNextEvent();
            eventsEmitted += engine.drainEvents().size();
            wal.add(new WalRecord.ClockAdvance(walSeq++, advancedTo, engine.worldHash()));
        }
        if (engine.nowMs() < untilMs) {
            engine.advanceTo(untilMs);
            eventsEmitted += engine.drainEvents().size();
            wal.add(new WalRecord.ClockAdvance(walSeq++, untilMs, engine.worldHash()));
        }

        // (4) Optionally export the admission stream as a replayable .jsonl WAL.
        if (outWal != null) {
            try {
                writeWal(Path.of(outWal), seed, wal);
            } catch (IOException | InvalidPathException ioFailure) {
                err.println("run: cannot write WAL \"" + outWal + "\": " + ioFailure);
                return HeadlessMain.EXIT_USAGE;
            }
        }

        out.println("run: seed=" + HeadlessMain.hex16(seed) + " owners=" + owners
                + " untilMs=" + untilMs + " walRecords=" + wal.size());
        out.println("logicalMs=" + engine.nowMs());
        out.println("eventsEmitted=" + eventsEmitted);
        out.println("worldHash=" + HeadlessMain.hex16(engine.worldHash()));
        return HeadlessMain.EXIT_OK;
    }

    private static void writeWal(Path target, long seed, List<WalRecord> wal)
            throws IOException {
        List<String> lines = new ArrayList<>(wal.size() + 1);
        lines.add(new WalHeader(seed, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION).encode());
        for (WalRecord record : wal) {
            lines.add(CommandCodec.encodeWal(record));
        }
        Files.write(target, (String.join("\n", lines) + "\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void requireAccepted(WildsEngine engine, WorldCommand command) {
        Phase1Result result = engine.apply(command);
        if (!(result instanceof Phase1Result.Accepted)) {
            throw new IllegalStateException("sample command rejected: " + command);
        }
    }

    private static int parseOwners(String raw) {
        long value = parseNonNegativeLong(raw, "--owners");
        if (value < 1) {
            throw new IllegalArgumentException("--owners must be >= 1: " + raw);
        }
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("--owners exceeds int range: " + raw);
        }
    }

    private static long parseNonNegativeLong(String raw, String flag) {
        try {
            long value = Long.parseLong(raw);
            if (value < 0) {
                throw new IllegalArgumentException(flag + " must be >= 0: " + raw);
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    flag + " must be a signed decimal long: \"" + raw + "\"");
        }
    }

    private static String takeValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        return args[index];
    }

    /** One registered test seat with its allocated owner/actor ids. */
    private record OwnerSeat(long seatKey, OwnerId ownerId, ActorId actorId) {
    }
}
