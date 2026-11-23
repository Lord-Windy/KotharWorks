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
package com.kotharworks.wilds.server.ports;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.domain.ControllerKind;

/**
 * Persistence port for the simulation world: permanent seat metadata, the
 * append-only command WAL (strictly pre-mutation parameters — Commit-Before-
 * Mutate needs zero post-apply {@code UPDATE}s), the idempotent event log with
 * its public projection, and checkpoint + WAL-tail recovery.
 *
 * <p>Every append method may throw on storage failure; {@code WorldHost}
 * translates that into a {@code STORAGE_UNAVAILABLE} outcome with the in-RAM
 * world untouched.
 */
public interface WorldStorePort {

    // ------------------------------------------------------------- seat metadata

    /**
     * Creates a permanent seat. The seat name is trimmed and must be 3..40
     * chars of non-ISO-control characters; HTML-significant characters
     * ({@code < > & "}) are deliberately allowed here — output escaping lives
     * in HTML rendering, not in storage validation.
     *
     * @throws IllegalArgumentException on blank/short/long/control names, or a
     *                                  duplicate seat name (message prefixed
     *                                  {@code DUPLICATE_SEAT})
     */
    SeatRecord createSeat(UserId owner, String seatName, ControllerKind kind);

    /** Every seat owned by {@code owner}, ascending by seat id. */
    List<SeatRecord> findSeatsByOwner(UserId owner);

    /** Every seat in the world, ascending by seat id (public observatory roster). */
    List<SeatRecord> findAllSeats();

    /** The seat with this id, or empty. */
    Optional<SeatRecord> findSeatById(long seatId);

    // ------------------------------------------------------------- world metadata

    /**
     * Returns the single active world, creating it on first call with the running
     * binary's pin, and applies the version gate against an existing one.
     *
     * <p>The stored active world <em>matches</em> when its seed, rules, engine and
     * protocol versions all equal the arguments and, <strong>only when the stored
     * fingerprint is non-null</strong>, its {@code rulesFingerprint} equals
     * {@code rulesFingerprint}. A legacy row with a {@code NULL} fingerprint matches on
     * the versions alone and is <em>not</em> backfilled: a matching world is returned
     * unchanged with zero writes. On a mismatch:
     * <ul>
     *   <li>{@link OnVersionMismatch#REFUSE} throws {@code WorldVersionMismatchException}
     *       and writes nothing;</li>
     *   <li>{@link OnVersionMismatch#ARCHIVE}, in one atomic step, marks the active world
     *       {@code ARCHIVED} (with {@code ended_at}) and inserts a new {@code ACTIVE}
     *       world carrying the running pin. It never touches the WAL, checkpoint or event
     *       rows of any world, so the archived world stays exportable.</li>
     * </ul>
     * Concurrent callers serialize: two archiving boots with the same running pin
     * archive the old world once, and the second sees the new world as a plain match.
     *
     * @throws com.kotharworks.wilds.server.domain.WorldVersionMismatchException on a
     *         mismatch in {@code REFUSE} mode
     */
    ActiveWorld ensureActiveWorld(long worldSeed, int rulesVersion, int engineVersion,
            int protocolVersion, long rulesFingerprint, OnVersionMismatch onMismatch);

    /**
     * {@link #ensureActiveWorld(long, int, int, int, long, OnVersionMismatch)} in
     * {@link OnVersionMismatch#REFUSE} mode, returning the active world.
     *
     * @throws com.kotharworks.wilds.server.domain.WorldVersionMismatchException on a
     *         mismatch with the stored active world
     */
    default WorldRecord ensureActiveWorld(long worldSeed, int rulesVersion, int engineVersion,
            int protocolVersion, long rulesFingerprint) {
        return ensureActiveWorld(worldSeed, rulesVersion, engineVersion, protocolVersion,
                rulesFingerprint, OnVersionMismatch.REFUSE).world();
    }

    // ------------------------------------------------------------- command WAL

    /**
     * Looks up a previously committed command by {@code (worldId, cmdId)} for
     * pre-checkpoint / post-eviction idempotency and seat-ownership checks.
     */
    Optional<WalRecord.CommandEntry> findCommandByCmdId(int worldId, UUID cmdId);

    /** Whether a command with this {@code cmdId} is already committed (pre-restart idempotency). */
    default boolean existsByCmdId(int worldId, UUID cmdId) {
        return findCommandByCmdId(worldId, cmdId).isPresent();
    }

    /**
     * Appends a {@code CLOCK_ADVANCE} anchor; Commit-Before-Mutate requires
     * this to hit the WAL <b>before</b> the engine clock moves.
     *
     * @return the assigned {@code walSeq}
     */
    long appendClockAdvance(int worldId, long admittedMs, Long worldHash);

    /**
     * Appends one admitted command; must be called before the engine mutates
     * state. Duplicate {@code cmdId}s are a host bug and rejected defensively.
     *
     * @return the assigned {@code walSeq}
     */
    long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId, WorldCommand cmd,
            Long worldHash);

    // ------------------------------------------------------------- event log

    /**
     * Persists events idempotently on {@code (worldId, cursor)}: cursors
     * already present are skipped, so checkpoint-replay backfill is a no-op
     * for stored rows.
     */
    void appendEvents(int worldId, List<StoredWorldEvent> events);

    /**
     * Reads events with {@code cursor > sinceCursor} ascending, up to
     * {@code limit} rows.
     *
     * @param seatIdOrNullForPublic when non-null, that seat's private events;
     *                              when null, only events carrying a
     *                              {@code publicSummary}, projected to public
     *                              fields only ({@code privateSummary} and the
     *                              decoded payload are structurally absent)
     */
    List<StoredWorldEvent> readEventsSince(int worldId, Long seatIdOrNullForPublic,
            long sinceCursor, int limit);

    /**
     * Reads the most recent events (up to {@code limit} rows), returned in
     * ascending {@code cursor} order so the last element carries the latest
     * cursor.
     *
     * @param seatIdOrNullForPublic when non-null, that seat's private events;
     *                              when null, only events carrying a
     *                              {@code publicSummary}, projected to public
     *                              fields only
     */
    List<StoredWorldEvent> readRecentEvents(int worldId, Long seatIdOrNullForPublic, int limit);

    // ------------------------------------------------------------- checkpoints & recovery

    /**
     * Persists a new checkpoint row. Durable stores append and retain the
     * latest ten rows per world (older rows are pruned); the in-memory
     * reference store keeps a single slot.
     */
    void saveCheckpoint(int worldId, long upToWalSeq, long logicalMs, long worldHash,
            String stateJson);

    /** The latest checkpoint of the world, or empty (fresh world). */
    Optional<StoredCheckpoint> loadLatestCheckpoint(int worldId);

    /** Every WAL row with {@code walSeq > afterWalSeq}, ascending by {@code walSeq}. */
    List<WalRecord> loadWalAfter(int worldId, long afterWalSeq);

    /**
     * Exports the world's full WAL as JSONL for {@code wilds-headless replay}:
     * line 1 is the canonical {@code WAL_HEADER} (world seed, version triple
     * and the stored rules fingerprint when recorded), then one canonical
     * {@link WalRecord} line per WAL row in ascending {@code walSeq} order.
     * Works for {@code ACTIVE} and {@code ARCHIVED} worlds alike.
     *
     * @throws IllegalStateException when no world row has this id (nothing
     *                               to export)
     */
    void exportWalJsonl(int worldId, Writer out) throws IOException;
}
