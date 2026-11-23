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
package com.kotharworks.wilds.server.adapters.postgres;

import java.io.IOException;
import java.io.Writer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.EventPayloadCodec;
import com.kotharworks.wilds.replay.WalHeader;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.PrintableText;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException;
import com.kotharworks.wilds.server.domain.WorldVersionMismatchException.Pin;
import com.kotharworks.wilds.server.ports.ActiveWorld;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Durable {@link WorldStorePort} over PostgreSQL via the JDBI3 Handle API.
 * {@code wilds_world} is 100% append-only: the WAL, the event log, and the
 * checkpoints only ever receive {@code INSERT}s — {@code wal_seq} is allocated
 * densely per {@code world_id} ({@code 1..N}, immune to rolled-back inserts and
 * sequence-cache gaps), event backfill is idempotent on
 * {@code (world_id, event_cursor)} via {@code ON CONFLICT DO NOTHING},
 * {@link #exportWalJsonl} streams via a server-side cursor in bounded memory,
 * and checkpoint retention keeps the latest ten rows per world.
 */
public final class PostgresWorldStore implements WorldStorePort {

    private static final long WORLD_META_LOCK_KEY = 0x6B77_574D_4554_4101L;
    private static final int WAL_LOCK_NAMESPACE = 0x6B77_5741;
    private static final int WAL_STREAM_FETCH_SIZE = 1_000;
    private static final String WORLD_COLUMNS = "world_id, world_seed, rules_version,"
            + " engine_version, protocol_version, rules_fingerprint, status";

    private final Jdbi jdbi;

    public PostgresWorldStore(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    }

    // ------------------------------------------------------------------ seat metadata

    @Override
    public SeatRecord createSeat(UserId owner, String seatName, ControllerKind kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        String trimmed = PrintableText.validateSeatName(seatName);
        long seatId;
        try {
            seatId = jdbi.withHandle(handle -> handle.select(
                            "INSERT INTO wilds_meta.seats (owner_user_id, seat_name,"
                                    + " controller_kind) VALUES (?, ?, ?) RETURNING seat_id",
                            owner.value(), trimmed, kind.name())
                    .mapTo(long.class).one());
        } catch (UnableToExecuteStatementException failure) {
            String state = sqlState(failure);
            if ("23505".equals(state)) {
                throw new IllegalArgumentException("DUPLICATE_SEAT: \"" + trimmed
                        + "\" is already chartered");
            }
            if ("23503".equals(state)) {
                throw new IllegalArgumentException("unknown owner: " + owner.value());
            }
            if ("23514".equals(state)) {
                throw new IllegalArgumentException("invalid seat definition: \"" + trimmed + "\"");
            }
            throw failure;
        }
        return new SeatRecord(seatId, owner, trimmed, kind);
    }

    @Override
    public List<SeatRecord> findSeatsByOwner(UserId owner) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT seat_id, owner_user_id, seat_name, controller_kind"
                                + " FROM wilds_meta.seats WHERE owner_user_id = ?"
                                + " ORDER BY seat_id", owner.value())
                .map((rs, ctx) -> seatRow(rs))
                .list());
    }

    @Override
    public List<SeatRecord> findAllSeats() {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT seat_id, owner_user_id, seat_name, controller_kind"
                                + " FROM wilds_meta.seats ORDER BY seat_id")
                .map((rs, ctx) -> seatRow(rs))
                .list());
    }

    @Override
    public Optional<SeatRecord> findSeatById(long seatId) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT seat_id, owner_user_id, seat_name, controller_kind"
                                + " FROM wilds_meta.seats WHERE seat_id = ?", seatId)
                .map((rs, ctx) -> seatRow(rs))
                .findFirst());
    }

    private static SeatRecord seatRow(java.sql.ResultSet rs) throws SQLException {
        return new SeatRecord(rs.getLong("seat_id"),
                new UserId(rs.getObject("owner_user_id", java.util.UUID.class)),
                rs.getString("seat_name"),
                ControllerKind.valueOf(rs.getString("controller_kind")));
    }

    // ------------------------------------------------------------------ world metadata

    /**
     * {@inheritDoc}
     *
     * <p>One transaction under {@code pg_advisory_xact_lock(WORLD_META_LOCK_KEY)}: the
     * lookup, the {@code ARCHIVED} update and the new-world {@code INSERT} commit or roll
     * back together, so a crash between them leaves the old world {@code ACTIVE} and two
     * concurrent archiving boots archive the old world exactly once. Only
     * {@code wilds_meta.worlds} is read or written; a refusal rolls back with zero writes.
     */
    @Override
    public ActiveWorld ensureActiveWorld(long worldSeed, int rulesVersion, int engineVersion,
            int protocolVersion, long rulesFingerprint, OnVersionMismatch onMismatch) {
        Objects.requireNonNull(onMismatch, "onMismatch");
        Pin running = new Pin(worldSeed, rulesVersion, engineVersion, protocolVersion,
                rulesFingerprint);
        return jdbi.inTransaction(handle -> {
            handle.execute("SELECT pg_advisory_xact_lock(?)", WORLD_META_LOCK_KEY);
            Optional<WorldRecord> found = selectActiveWorld(handle);
            if (found.isEmpty()) {
                return new ActiveWorld(insertWorld(handle, running), null);
            }
            WorldRecord active = found.get();
            Pin stored = Pin.of(active);
            if (stored.admits(running)) {
                return new ActiveWorld(active, null);
            }
            if (onMismatch == OnVersionMismatch.REFUSE) {
                throw new WorldVersionMismatchException(active.worldId(), stored, running);
            }
            int archivedRows = handle.createUpdate("UPDATE wilds_meta.worlds"
                            + " SET status = 'ARCHIVED', ended_at = now()"
                            + " WHERE world_id = :worldId AND status = 'ACTIVE'")
                    .bind("worldId", active.worldId())
                    .execute();
            if (archivedRows != 1) {
                throw new IllegalStateException("archiving world " + active.worldId()
                        + " updated " + archivedRows + " rows, expected exactly 1");
            }
            WorldRecord archived = new WorldRecord(active.worldId(), active.worldSeed(),
                    active.rulesVersion(), active.engineVersion(), active.protocolVersion(),
                    active.rulesFingerprint(), WorldRecord.ARCHIVED);
            return new ActiveWorld(insertWorld(handle, running), archived);
        });
    }

    private static WorldRecord insertWorld(org.jdbi.v3.core.Handle handle, Pin pin) {
        return handle.select("INSERT INTO wilds_meta.worlds (title, world_seed,"
                        + " rules_version, engine_version, protocol_version, rules_fingerprint,"
                        + " jvm_version) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + WORLD_COLUMNS,
                "Ironwood Wilds", pin.worldSeed(), pin.rulesVersion(), pin.engineVersion(),
                pin.protocolVersion(), pin.rulesFingerprint(), Runtime.version().toString())
                .map((rs, ctx) -> worldRow(rs))
                .one();
    }

    private static WorldRecord worldRow(java.sql.ResultSet rs) throws SQLException {
        return new WorldRecord(rs.getInt("world_id"), rs.getLong("world_seed"),
                rs.getInt("rules_version"), rs.getInt("engine_version"),
                rs.getInt("protocol_version"), rs.getObject("rules_fingerprint", Long.class),
                rs.getString("status"));
    }

    private static Optional<WorldRecord> selectActiveWorld(org.jdbi.v3.core.Handle handle) {
        return handle.select("SELECT " + WORLD_COLUMNS + " FROM wilds_meta.worlds"
                        + " WHERE status = 'ACTIVE' ORDER BY world_id DESC LIMIT 1")
                .map((rs, ctx) -> worldRow(rs))
                .findFirst();
    }

    // ------------------------------------------------------------------ command WAL

    @Override
    public Optional<WalRecord.CommandEntry> findCommandByCmdId(int worldId, UUID cmdId) {
        Objects.requireNonNull(cmdId, "cmdId");
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT wal_seq, admitted_ms, seat_id, cmd_id::text AS cmd_id,"
                                + " payload_json::text AS payload, world_hash"
                                + " FROM wilds_world.command_wal"
                                + " WHERE world_id = ? AND cmd_id = ?",
                        worldId, cmdId)
                .map((rs, ctx) -> new WalRecord.CommandEntry(
                        rs.getLong("wal_seq"),
                        rs.getLong("admitted_ms"),
                        rs.getLong("seat_id"),
                        rs.getString("cmd_id"),
                        CommandCodec.decode(rs.getString("payload")),
                        rs.getObject("world_hash", Long.class)))
                .findFirst());
    }

    @Override
    public boolean existsByCmdId(int worldId, UUID cmdId) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT EXISTS (SELECT 1 FROM wilds_world.command_wal"
                                + " WHERE world_id = ? AND cmd_id = ?) AS present",
                        worldId, cmdId)
                .mapTo(boolean.class).one());
    }

    @Override
    public long appendClockAdvance(int worldId, long admittedMs, Long worldHash) {
        return jdbi.inTransaction(handle -> {
            handle.execute("SELECT pg_advisory_xact_lock(?, ?)", WAL_LOCK_NAMESPACE, worldId);
            return handle.select(
                            "INSERT INTO wilds_world.command_wal (world_id, wal_seq, admitted_ms,"
                                    + " cmd_type, payload_json, world_hash)"
                                    + " VALUES (?, COALESCE((SELECT max(wal_seq)"
                                    + " FROM wilds_world.command_wal WHERE world_id = ?), 0) + 1,"
                                    + " ?, 'CLOCK_ADVANCE', '{}'::jsonb, ?) RETURNING wal_seq",
                            worldId, worldId, admittedMs, worldHash)
                    .mapTo(long.class).one();
        });
    }

    @Override
    public long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId,
            WorldCommand cmd, Long worldHash) {
        Objects.requireNonNull(cmdId, "cmdId");
        Objects.requireNonNull(cmd, "cmd");
        String type = cmdType(cmd);
        String encoded = CommandCodec.encode(cmd);
        try {
            return jdbi.inTransaction(handle -> {
                handle.execute("SELECT pg_advisory_xact_lock(?, ?)", WAL_LOCK_NAMESPACE, worldId);
                return handle.select(
                                "INSERT INTO wilds_world.command_wal (world_id, wal_seq,"
                                        + " admitted_ms, seat_id, cmd_id, cmd_type,"
                                        + " payload_json, world_hash)"
                                        + " VALUES (?, COALESCE((SELECT max(wal_seq)"
                                        + " FROM wilds_world.command_wal WHERE world_id = ?), 0)"
                                        + " + 1, ?, ?, ?, ?, ?::jsonb, ?) RETURNING wal_seq",
                                worldId, worldId, admittedMs, seatId, cmdId, type, encoded,
                                worldHash)
                        .mapTo(long.class).one();
            });
        } catch (UnableToExecuteStatementException failure) {
            if ("23505".equals(sqlState(failure))) {
                throw new IllegalStateException("duplicate cmdId in WAL: " + cmdId);
            }
            throw failure;
        }
    }

    /** The {@code cmd_type} wire string; must match {@link CommandCodec}'s encoding. */
    private static String cmdType(WorldCommand cmd) {
        return switch (cmd) {
            case WorldCommand.RegisterOwner c -> "registerOwner";
            case WorldCommand.EnqueueTasks c -> "enqueueTasks";
            case WorldCommand.ReplacePendingQueue c -> "replacePendingQueue";
            case WorldCommand.CancelQueue c -> "cancelQueue";
            case WorldCommand.SetFailurePolicy c -> "setFailurePolicy";
        };
    }

    // ------------------------------------------------------------------ event log

    @Override
    public void appendEvents(int worldId, List<StoredWorldEvent> events) {
        Objects.requireNonNull(events, "events");
        if (events.isEmpty()) {
            return;
        }
        jdbi.useTransaction(handle -> {
            try (var batch = handle.prepareBatch(
                    "INSERT INTO wilds_world.world_events (world_id, event_cursor,"
                            + " occurred_ms, kind, seat_id, public_summary, private_summary,"
                            + " payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
                            + " ON CONFLICT (world_id, event_cursor) DO NOTHING")) {
                for (StoredWorldEvent event : events) {
                    batch.bind(0, worldId)
                            .bind(1, event.cursor())
                            .bind(2, event.occurredMs())
                            .bind(3, event.kind())
                            .bindByType(4, event.seatId(), Long.class)
                            .bindByType(5, event.publicSummary(), String.class)
                            .bind(6, event.privateSummary())
                            .bind(7, EventPayloadCodec.encode(event.event()))
                            .add();
                }
                batch.execute();
            }
        });
    }

    @Override
    public List<StoredWorldEvent> readEventsSince(int worldId, Long seatIdOrNullForPublic,
            long sinceCursor, int limit) {
        StringBuilder sql = new StringBuilder("SELECT event_cursor, occurred_ms, kind, seat_id,"
                + " public_summary, private_summary, payload_json::text AS payload"
                + " FROM wilds_world.world_events WHERE world_id = ? AND event_cursor > ?");
        List<Object> binds = new ArrayList<>(List.of(worldId, sinceCursor));
        if (seatIdOrNullForPublic != null) {
            sql.append(" AND seat_id = ?");
            binds.add(seatIdOrNullForPublic);
        } else {
            sql.append(" AND public_summary IS NOT NULL");
        }
        sql.append(" ORDER BY event_cursor LIMIT ?");
        binds.add(limit);
        return jdbi.withHandle(handle -> handle.select(sql.toString(), binds.toArray())
                .map((rs, ctx) -> eventRow(rs, seatIdOrNullForPublic != null))
                .list());
    }

    @Override
    public List<StoredWorldEvent> readRecentEvents(int worldId, Long seatIdOrNullForPublic,
            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got: " + limit);
        }
        StringBuilder sql = new StringBuilder("SELECT event_cursor, occurred_ms, kind, seat_id,"
                + " public_summary, private_summary, payload_json::text AS payload"
                + " FROM wilds_world.world_events WHERE world_id = ?");
        List<Object> binds = new ArrayList<>(List.of(worldId));
        if (seatIdOrNullForPublic != null) {
            sql.append(" AND seat_id = ?");
            binds.add(seatIdOrNullForPublic);
        } else {
            sql.append(" AND public_summary IS NOT NULL");
        }
        sql.append(" ORDER BY event_cursor DESC LIMIT ?");
        binds.add(limit);
        List<StoredWorldEvent> descending = jdbi.withHandle(handle ->
                handle.select(sql.toString(), binds.toArray())
                        .map((rs, ctx) -> eventRow(rs, seatIdOrNullForPublic != null))
                        .list());
        List<StoredWorldEvent> ascending = new ArrayList<>(descending);
        Collections.reverse(ascending);
        return List.copyOf(ascending);
    }

    private static StoredWorldEvent eventRow(java.sql.ResultSet rs, boolean privateSeatView)
            throws SQLException {
        long cursor = rs.getLong("event_cursor");
        long occurredMs = rs.getLong("occurred_ms");
        String kind = rs.getString("kind");
        String publicSummary = rs.getString("public_summary");
        String privateSummary = rs.getString("private_summary");
        if (privateSeatView) {
            return StoredWorldEvent.full(cursor, occurredMs, kind,
                    rs.getLong("seat_id"), publicSummary, privateSummary,
                    EventPayloadCodec.decode(kind, cursor, occurredMs,
                            publicSummary, privateSummary, rs.getString("payload")));
        }
        return StoredWorldEvent.publicProjection(new StoredWorldEvent(cursor,
                occurredMs, kind, null, publicSummary, null, null));
    }

    // ------------------------------------------------------------------ checkpoints & recovery

    @Override
    public void saveCheckpoint(int worldId, long upToWalSeq, long logicalMs, long worldHash,
            String stateJson) {
        jdbi.useHandle(handle -> {
            handle.execute("INSERT INTO wilds_world.world_checkpoints (world_id, up_to_wal_seq,"
                            + " logical_ms, world_hash, state_json) VALUES (?, ?, ?, ?, ?::jsonb)",
                    worldId, upToWalSeq, logicalMs, worldHash, stateJson);
            handle.execute("DELETE FROM wilds_world.world_checkpoints WHERE world_id = ?"
                            + " AND checkpoint_id NOT IN (SELECT checkpoint_id"
                            + " FROM wilds_world.world_checkpoints WHERE world_id = ?"
                            + " ORDER BY checkpoint_id DESC LIMIT 10)",
                    worldId, worldId);
        });
    }

    @Override
    public Optional<StoredCheckpoint> loadLatestCheckpoint(int worldId) {
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT checkpoint_id, up_to_wal_seq, logical_ms, world_hash,"
                                + " state_json::text AS state FROM wilds_world.world_checkpoints"
                                + " WHERE world_id = ? ORDER BY checkpoint_id DESC LIMIT 1",
                        worldId)
                .map((rs, ctx) -> new StoredCheckpoint(rs.getLong("checkpoint_id"),
                        rs.getLong("up_to_wal_seq"), rs.getLong("logical_ms"),
                        rs.getLong("world_hash"), rs.getString("state")))
                .findFirst());
    }

    @Override
    public List<WalRecord> loadWalAfter(int worldId, long afterWalSeq) {
        return jdbi.inTransaction(handle -> handle.select(
                        "SELECT wal_seq, admitted_ms, seat_id, cmd_id::text AS cmd_id, cmd_type,"
                                + " payload_json::text AS payload, world_hash"
                                + " FROM wilds_world.command_wal WHERE world_id = ?"
                                + " AND wal_seq > ? ORDER BY wal_seq",
                        worldId, afterWalSeq)
                .setFetchSize(WAL_STREAM_FETCH_SIZE)
                .map((rs, ctx) -> walRow(rs))
                .list());
    }

    @Override
    public void exportWalJsonl(int worldId, Writer out) throws IOException {
        Objects.requireNonNull(out, "out");
        jdbi.useTransaction(handle -> {
            WorldRecord world = handle.select(
                            "SELECT " + WORLD_COLUMNS + " FROM wilds_meta.worlds"
                                    + " WHERE world_id = ?",
                            worldId)
                    .map((rs, ctx) -> worldRow(rs))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "no world row to export: " + worldId));
            out.write(new WalHeader(world.worldSeed(), world.rulesVersion(),
                    world.engineVersion(), world.rulesFingerprint()).encode());
            out.write('\n');
            try (var iter = handle.select(
                            "SELECT wal_seq, admitted_ms, seat_id, cmd_id::text AS cmd_id,"
                                    + " cmd_type, payload_json::text AS payload, world_hash"
                                    + " FROM wilds_world.command_wal WHERE world_id = ?"
                                    + " ORDER BY wal_seq",
                            worldId)
                    .setFetchSize(WAL_STREAM_FETCH_SIZE)
                    .map((rs, ctx) -> walRow(rs))
                    .iterator()) {
                while (iter.hasNext()) {
                    out.write(CommandCodec.encodeWal(iter.next()));
                    out.write('\n');
                }
            }
        });
    }

    private static WalRecord walRow(java.sql.ResultSet rs) throws SQLException {
        long walSeq = rs.getLong("wal_seq");
        long admittedMs = rs.getLong("admitted_ms");
        Long worldHash = rs.getObject("world_hash", Long.class);
        if ("CLOCK_ADVANCE".equals(rs.getString("cmd_type"))) {
            return new WalRecord.ClockAdvance(walSeq, admittedMs, worldHash);
        }
        return new WalRecord.CommandEntry(walSeq, admittedMs,
                rs.getLong("seat_id"), rs.getString("cmd_id"),
                CommandCodec.decode(rs.getString("payload")), worldHash);
    }

    // ------------------------------------------------------------------ helpers

    /** Extracts the SQLSTATE from the exception cause chain (null when absent). */
    private static String sqlState(UnableToExecuteStatementException failure) {
        Throwable cause = failure.getCause();
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return null;
    }
}
