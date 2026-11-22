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

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Entry point of the zero-server headless CLI over the pure {@code WildsEngine}
 * (Ticket 05): {@code run} fast-forwards a seeded sample world in milliseconds,
 * {@code replay} re-drives a {@code .jsonl} WAL with bit-for-bit {@code WorldHash}
 * parity, and {@code inspect} prints the seeded bootstrap topology with its
 * {@code glance}/{@code look}/{@code examine} stub descriptions.
 *
 * <p>Exit codes: {@code 0} success, {@code 1} usage or I/O failure, {@code 2} WAL
 * header version mismatch, {@code 3} WAL integrity failure (corrupted JSON, header
 * {@code worldSeed} mismatch, inadmissible command, non-ascending {@code walSeq},
 * clock regression, or inline {@code worldHash} mismatch).
 *
 * <p>Tests drive the CLI in-process via {@link #execute(String[], PrintWriter,
 * PrintWriter)}; {@link #main} only wires System.out/System.err up to it.
 */
public final class HeadlessMain {

    /** Subcommand finished successfully. */
    public static final int EXIT_OK = 0;

    /** Bad arguments, unknown subcommand, or file I/O failure. */
    public static final int EXIT_USAGE = 1;

    /** WAL header carries an unsupported {@code rulesVersion} or {@code engineVersion}. */
    public static final int EXIT_VERSION_MISMATCH = 2;

    /** WAL body or header seed diverges from expected integrity invariants. */
    public static final int EXIT_WAL_INTEGRITY = 3;

    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final HexFormat HEX = HexFormat.of();

    private HeadlessMain() {
    }

    /** CLI entry point: runs {@link #execute} against stdout/stderr and exits. */
    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                System.out, StandardCharsets.UTF_8)));
        PrintWriter err = new PrintWriter(new BufferedWriter(new OutputStreamWriter(
                System.err, StandardCharsets.UTF_8)));
        int exitCode;
        try {
            exitCode = execute(args, out, err);
        } catch (RuntimeException failure) {
            err.println("headless: internal error: " + failure);
            exitCode = EXIT_USAGE;
        }
        out.flush();
        err.flush();
        // exec-maven-plugin (exec:java) invokes main inside the mvnd daemon JVM via an
        // isolated URLClassLoader; calling System.exit there kills the daemon process.
        // Return normally on EXIT_OK and throw on non-zero so Maven fails the build
        // cleanly without crashing mvnd; standalone JVM launches get exact exit codes.
        if (runningInsideExecJava()) {
            if (exitCode != EXIT_OK) {
                throw new IllegalStateException("headless exited with code " + exitCode);
            }
            return;
        }
        System.exit(exitCode);
    }

    /** True when invoked in-process by {@code exec-maven-plugin} ({@code exec:java}). */
    static boolean runningInsideExecJava() {
        return STACK_WALKER.walk(frames -> frames.anyMatch(
                frame -> frame.getClassName().startsWith("org.codehaus.mojo.exec.")));
    }

    /**
     * Runs one CLI invocation in-process against the given sinks and returns the exit
     * code. Never calls {@code System.exit}; flushes (but never closes) the writers.
     */
    public static int execute(String[] args, PrintWriter out, PrintWriter err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        try {
            if (args.length == 0) {
                printUsage(err);
                return EXIT_USAGE;
            }
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            return switch (args[0]) {
                case "run" -> RunCommand.execute(rest, out, err);
                case "replay" -> ReplayCommand.execute(rest, out, err);
                case "inspect" -> InspectCommand.execute(rest, out, err);
                case "--help", "-h", "help" -> {
                    printUsage(out);
                    yield EXIT_OK;
                }
                default -> {
                    err.println("headless: unknown subcommand \"" + args[0] + "\"");
                    printUsage(err);
                    yield EXIT_USAGE;
                }
            };
        } finally {
            out.flush();
            err.flush();
        }
    }

    /**
     * Parses a {@code --seed} value: signed decimal {@code long}, or full 64-bit
     * unsigned {@code 0x}/{@code 0X} hex of 1..16 hex digits (via
     * {@link Long#parseUnsignedLong} so 16-digit hashes with bit 63 set never overflow,
     * while rejecting signs or >16-digit strings).
     *
     * @throws IllegalArgumentException when {@code raw} is neither form
     */
    static long parseSeed(String raw) {
        Objects.requireNonNull(raw, "raw");
        String trimmed = raw.trim();
        if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
            String hex = trimmed.substring(2);
            if (hex.isEmpty() || hex.length() > 16 || !allAsciiHexDigits(hex)) {
                throw new IllegalArgumentException("invalid hex seed \"" + raw
                        + "\" (expected 1..16 hex digits after 0x)");
            }
            return Long.parseUnsignedLong(hex, 16);
        }
        try {
            return Long.parseLong(trimmed);
        } catch (NumberFormatException malformedDecimal) {
            throw new IllegalArgumentException("invalid seed \"" + raw
                    + "\" (expected signed decimal or 0x-prefixed hex)");
        }
    }

    private static boolean allAsciiHexDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }

    /** Expands {@code --flag=value} tokens so both {@code --flag value} and {@code --flag=value} work. */
    static List<String> expandEqualsFlags(String[] rawArgs) {
        List<String> expanded = new ArrayList<>(rawArgs.length * 2);
        for (String arg : rawArgs) {
            Objects.requireNonNull(arg, "arg");
            int eq = arg.startsWith("--") ? arg.indexOf('=') : -1;
            if (eq > 2) {
                expanded.add(arg.substring(0, eq));
                expanded.add(arg.substring(eq + 1));
            } else {
                expanded.add(arg);
            }
        }
        return expanded;
    }

    /**
     * Reads the value token for {@code flag}, rejecting duplicate occurrences, missing
     * trailing values, and omitted values immediately followed by another {@code --flag}.
     */
    static String takeFlagValue(List<String> args, int valueIndex, String flag,
                                String previousValue) {
        if (previousValue != null) {
            throw new IllegalArgumentException("duplicate flag " + flag);
        }
        if (valueIndex >= args.size()) {
            throw new IllegalArgumentException("missing value for " + flag);
        }
        String value = args.get(valueIndex);
        if (value.startsWith("--")) {
            throw new IllegalArgumentException("missing value for " + flag
                    + " (got flag \"" + value + "\")");
        }
        return value;
    }

    /** Parses a non-negative signed decimal {@code long} for CLI flags. */
    static long parseNonNegativeLong(String raw, String flag) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException(flag + " must be >= 0: " + raw);
            }
            return value;
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    flag + " must be a signed decimal long: \"" + raw + "\"");
        }
    }

    /** Canonical locale-independent CLI digest form: {@code 0x} + 16 lowercase hex digits. */
    static String hex16(long value) {
        return "0x" + HEX.toHexDigits(value);
    }

    static void printUsage(PrintWriter sink) {
        sink.println("usage: headless <subcommand> [flags]");
        sink.println("  run --seed <long|0xHex> [--owners <n>] [--until-ms <ms>] [--out-wal <file>]");
        sink.println("      Registers owners, queues sample Travel/Wait loops, fast-forwards via");
        sink.println("      advanceToNextEvent() up to --until-ms, optionally writes the .jsonl WAL,");
        sink.println("      and prints logicalMs, eventsEmitted and worldHash=0x<16-hex>.");
        sink.println("  replay --seed <long|0xHex> --wal <file> [--to-ms <ms>] [--hash]");
        sink.println("      Replays the .jsonl WAL deterministically (stopping at --to-ms if given),");
        sink.println("      verifying pinned worldHashes inline; --hash prints the final worldHash line.");
        sink.println("  inspect --seed <long|0xHex>");
        sink.println("      Prints the seeded bootstrap place graph, edge travel times, and the");
        sink.println("      GLANCE/LOOK/EXAMINE description of every bootstrap place.");
        sink.println("exit codes: 0 ok | 1 usage/IO | 2 WAL version mismatch | 3 WAL integrity");
    }
}
