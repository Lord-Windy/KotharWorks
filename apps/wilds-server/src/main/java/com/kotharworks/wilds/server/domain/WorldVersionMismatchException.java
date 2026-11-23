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
package com.kotharworks.wilds.server.domain;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;

import com.kotharworks.wilds.rules.RulesFingerprint;
import com.kotharworks.wilds.server.ports.WorldRecord;

/**
 * The stored active world was pinned by a different binary or {@code --seed} than
 * the one booting: its seed, rules, engine or protocol version differ, or its recorded rules
 * fingerprint differs from the running catalog's. Thrown by the world store in
 * {@code refuse} mode with nothing written, and on PostgreSQL also by
 * {@code PreMigrationVersionGate} before any pending migration runs, so a refused boot
 * leaves every schema (including {@code public.schema_migrations}) untouched;
 * {@code Main} renders {@link #diagnostic()} on stderr and exits {@code 3}.
 *
 * <p>Extends {@link IllegalStateException} so pre-existing callers that treated a
 * version-triple mismatch as an {@code IllegalStateException} keep working.
 */
public final class WorldVersionMismatchException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * One side of the comparison: the pin a world was created with, or the pin of
     * the running binary.
     *
     * @param rulesFingerprint the rules catalog fingerprint, or {@code null} when the
     *                         stored world predates fingerprint recording
     */
    public record Pin(long worldSeed, int rulesVersion, int engineVersion, int protocolVersion,
            Long rulesFingerprint) implements Serializable {

        /** The pin recorded on a stored world. */
        public static Pin of(WorldRecord world) {
            return new Pin(world.worldSeed(), world.rulesVersion(), world.engineVersion(),
                    world.protocolVersion(), world.rulesFingerprint());
        }

        /**
         * Whether a world stored with this pin may be simulated by a binary with the
         * {@code running} pin: seed and all three versions must be equal, and the
         * fingerprint must be equal <em>when this (stored) fingerprint is non-null</em>.
         * A legacy {@code null} stored fingerprint matches on the versions alone.
         */
        public boolean admits(Pin running) {
            return worldSeed == running.worldSeed && rulesVersion == running.rulesVersion
                    && engineVersion == running.engineVersion
                    && protocolVersion == running.protocolVersion
                    && (rulesFingerprint == null
                            || rulesFingerprint.equals(running.rulesFingerprint));
        }
    }

    private final int storedWorldId;
    private final Pin stored;
    private final Pin running;

    public WorldVersionMismatchException(int storedWorldId, Pin stored, Pin running) {
        super(summary(storedWorldId, Objects.requireNonNull(stored, "stored"),
                Objects.requireNonNull(running, "running")));
        this.storedWorldId = storedWorldId;
        this.stored = stored;
        this.running = running;
    }

    /** The {@code world_id} of the stored active world that refused this binary. */
    public int storedWorldId() {
        return storedWorldId;
    }

    /** The pin recorded for the stored active world. */
    public Pin stored() {
        return stored;
    }

    /** The pin of the running binary (and requested seed). */
    public Pin running() {
        return running;
    }

    /**
     * A multi-line, human-readable report of stored vs. running values and the two
     * remediations, for operators (no stack trace needed).
     */
    public String diagnostic() {
        StringBuilder out = new StringBuilder();
        out.append("active world ").append(storedWorldId)
                .append(" does not match this binary and --seed; refusing to boot.\n");
        out.append(String.format(Locale.ROOT, "  %-18s %-22s %s\n", "", "stored", "running"));
        row(out, "worldSeed", Long.toString(stored.worldSeed()),
                Long.toString(running.worldSeed()), stored.worldSeed() != running.worldSeed());
        row(out, "rulesVersion", Integer.toString(stored.rulesVersion()),
                Integer.toString(running.rulesVersion()),
                stored.rulesVersion() != running.rulesVersion());
        row(out, "engineVersion", Integer.toString(stored.engineVersion()),
                Integer.toString(running.engineVersion()),
                stored.engineVersion() != running.engineVersion());
        row(out, "protocolVersion", Integer.toString(stored.protocolVersion()),
                Integer.toString(running.protocolVersion()),
                stored.protocolVersion() != running.protocolVersion());
        // A legacy (unset) stored fingerprint is not compared, so it is never flagged.
        row(out, "rulesFingerprint", hex(stored.rulesFingerprint()),
                hex(running.rulesFingerprint()), stored.rulesFingerprint() != null
                        && !stored.rulesFingerprint().equals(running.rulesFingerprint()));
        out.append("remediation:\n");
        out.append("  - run the wilds-server binary (and --seed) that matches the stored world,"
                + " or\n");
        out.append("  - restart with --on-version-mismatch=archive to archive world ")
                .append(storedWorldId)
                .append(" (its WAL, checkpoints and events are kept) and start a fresh one.");
        return out.toString();
    }

    private static void row(StringBuilder out, String name, String stored, String running,
            boolean differs) {
        out.append(String.format(Locale.ROOT, "  %-18s %-22s %s%s\n", name, stored, running,
                differs ? "   <- differs" : ""));
    }

    private static String hex(Long fingerprint) {
        return fingerprint == null ? "unset" : RulesFingerprint.toHex(fingerprint);
    }

    private static String summary(int worldId, Pin stored, Pin running) {
        return "active world " + worldId + " version mismatch: stored seed/rules/engine/protocol/"
                + "fingerprint " + stored.worldSeed() + "/" + stored.rulesVersion() + "/"
                + stored.engineVersion() + "/" + stored.protocolVersion() + "/"
                + hex(stored.rulesFingerprint()) + ", running " + running.worldSeed() + "/"
                + running.rulesVersion() + "/" + running.engineVersion() + "/"
                + running.protocolVersion() + "/" + hex(running.rulesFingerprint());
    }
}
