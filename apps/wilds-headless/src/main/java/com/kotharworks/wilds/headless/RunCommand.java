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
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.state.PlaceEdge;
import com.kotharworks.wilds.state.PlaceRow;
import com.kotharworks.wilds.state.TaskSpec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code run --seed <long|0xHex> [--owners <n>] [--until-ms <ms>] [--out-wal <file>]}:
 * registers {@code <n>} test owner seats at the home place, queues a sample
 * {@code Travel}/{@code Wait} loop for every actor, fast-forwards the pure engine via
 * {@code advanceToNextEvent()} up to {@code --until-ms} (zero idle cost), optionally
 * streams the exact admission log as a {@code .jsonl} WAL, and prints the final
 * {@code logicalMs}, {@code eventsEmitted}, and {@code worldHash=0x<16-hex>}.
 */
final class RunCommand {

    /** Default seat count when {@code --owners} is omitted. */
    static final int DEFAULT_OWNERS = 2;

    /** Default fast-forward horizon when {@code --until-ms} is omitted (1 logical hour). */
    static final long DEFAULT_UNTIL_MS = 3_600_000L;

    /** Sample loop wait: {@code 2_000ms} per batch, {@code 2} batches. */
    private static final TaskSpec.Wait SAMPLE_WAIT = new TaskSpec.Wait(2_000L, 2);

    private RunCommand() {
    }

    static int execute(String[] rawArgs, PrintWriter out, PrintWriter err) {
        String seedRaw = null;
        String ownersRaw = null;
        String untilMsRaw = null;
        String outWal = null;
        try {
            List<String> args = HeadlessMain.expandEqualsFlags(rawArgs);
            for (int i = 0; i < args.size(); i++) {
                String token = args.get(i);
                switch (token) {
                    case "--seed" ->
                            seedRaw = HeadlessMain.takeFlagValue(args, ++i, "--seed", seedRaw);
                    case "--owners" ->
                            ownersRaw = HeadlessMain.takeFlagValue(args, ++i, "--owners", ownersRaw);
                    case "--until-ms" ->
                            untilMsRaw = HeadlessMain.takeFlagValue(args, ++i, "--until-ms", untilMsRaw);
                    case "--out-wal" ->
                            outWal = HeadlessMain.takeFlagValue(args, ++i, "--out-wal", outWal);
                    case "--help", "-h" -> {
                        HeadlessMain.printUsage(out);
                        return HeadlessMain.EXIT_OK;
                    }
                    default -> throw new IllegalArgumentException(
                            "unknown run flag \"" + token + "\"");
                }
            }
            if (seedRaw == null) {
                throw new IllegalArgumentException("missing required flag --seed");
            }
            long seed = HeadlessMain.parseSeed(seedRaw);
            int owners = ownersRaw == null ? DEFAULT_OWNERS : parseOwners(ownersRaw);
            long untilMs = untilMsRaw == null
                    ? DEFAULT_UNTIL_MS
                    : HeadlessMain.parseNonNegativeLong(untilMsRaw, "--until-ms");
            return run(seed, owners, untilMs, outWal, out, err);
        } catch (IllegalArgumentException badArgs) {
            err.println("run: " + badArgs.getMessage());
            return HeadlessMain.EXIT_USAGE;
        }
    }

    private static int run(long seed, int owners, long untilMs, String outWal,
                           PrintWriter out, PrintWriter err) {
        WildsEngine engine = new WildsEngine(new WorldSeed(seed));
        PlaceId loopDestination = firstAdjacentPlace(engine);
        long eventsEmitted = 0;
        long walSeq = 1;

        try (BufferedWriter walWriter = outWal == null
                ? null
                : Files.newBufferedWriter(Path.of(outWal), StandardCharsets.UTF_8)) {
            if (walWriter != null) {
                walWriter.write(new WalHeader(seed, RuleRegistry.WILDS_RULES_VERSION,
                        WildsEngine.API_VERSION).encode());
                walWriter.write('\n');
            }

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
                long seq = walSeq++;
                if (walWriter != null) {
                    writeRecord(walWriter, new WalRecord.CommandEntry(seq, engine.nowMs(), i,
                            "headless-" + seq, command, engine.worldHash()));
                }
            }

            // (2) Queue the sample Travel/Wait loop for every actor, still at t=0.
            for (OwnerSeat seat : seats) {
                WorldCommand.EnqueueTasks command = new WorldCommand.EnqueueTasks(
                        seat.ownerId(), seat.actorId(), null,
                        List.of(new TaskSpec.Travel(loopDestination), SAMPLE_WAIT,
                                new TaskSpec.Travel(WildsEngine.HOME_PLACE_ID)));
                requireAccepted(engine, command);
                eventsEmitted += engine.drainEvents().size();
                long seq = walSeq++;
                if (walWriter != null) {
                    writeRecord(walWriter, new WalRecord.CommandEntry(seq, engine.nowMs(),
                            seat.seatKey(), "headless-" + seq, command, engine.worldHash()));
                }
            }

            // (3) Fast-forward: one engine step per distinct due timestamp, zero idle cost.
            while (engine.peekNextDueMs().isPresent()
                    && engine.peekNextDueMs().getAsLong() <= untilMs) {
                long advancedTo = engine.advanceToNextEvent();
                eventsEmitted += engine.drainEvents().size();
                long seq = walSeq++;
                if (walWriter != null) {
                    writeRecord(walWriter, new WalRecord.ClockAdvance(seq, advancedTo,
                            engine.worldHash()));
                }
            }
            if (engine.nowMs() < untilMs) {
                engine.advanceTo(untilMs);
                eventsEmitted += engine.drainEvents().size();
                long seq = walSeq++;
                if (walWriter != null) {
                    writeRecord(walWriter, new WalRecord.ClockAdvance(seq, untilMs,
                            engine.worldHash()));
                }
            }
        } catch (IOException | InvalidPathException ioFailure) {
            err.println("run: cannot write WAL \"" + outWal + "\": " + ioFailure);
            return HeadlessMain.EXIT_USAGE;
        }

        out.println("run: seed=" + HeadlessMain.hex16(seed) + " owners=" + owners
                + " untilMs=" + untilMs + " walRecords=" + (walSeq - 1));
        out.println("logicalMs=" + engine.nowMs());
        out.println("eventsEmitted=" + eventsEmitted);
        out.println("worldHash=" + HeadlessMain.hex16(engine.worldHash()));
        return HeadlessMain.EXIT_OK;
    }

    private static PlaceId firstAdjacentPlace(WildsEngine engine) {
        PlaceRow home = engine.view().place(WildsEngine.HOME_PLACE_ID).orElseThrow();
        List<PlaceEdge> edges = home.edges();
        if (edges.isEmpty()) {
            throw new IllegalStateException("home place " + home.id().toWire()
                    + " has no outgoing edges");
        }
        return edges.getFirst().to();
    }

    private static void writeRecord(BufferedWriter walWriter, WalRecord record)
            throws IOException {
        walWriter.write(CommandCodec.encodeWal(record));
        walWriter.write('\n');
    }

    private static void requireAccepted(WildsEngine engine, WorldCommand command) {
        Phase1Result result = engine.apply(command);
        if (!(result instanceof Phase1Result.Accepted)) {
            throw new IllegalStateException("sample command rejected: " + command);
        }
    }

    private static int parseOwners(String raw) {
        long value = HeadlessMain.parseNonNegativeLong(raw, "--owners");
        if (value < 1) {
            throw new IllegalArgumentException("--owners must be >= 1: " + raw);
        }
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("--owners exceeds int range: " + raw);
        }
    }

    /** One registered test seat with its allocated owner/actor ids. */
    private record OwnerSeat(long seatKey, OwnerId ownerId, ActorId actorId) {
    }
}
