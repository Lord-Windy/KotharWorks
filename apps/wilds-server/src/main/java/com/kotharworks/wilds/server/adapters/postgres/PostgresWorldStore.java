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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.replay.CommandCodec;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WalJsonlCodec;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Durable {@link WorldStorePort} over PostgreSQL via the JDBI3 Handle API.
 * {@code wilds_world} is 100% append-only: the WAL, the event log, and the
 * checkpoints only ever receive {@code INSERT}s — event backfill is idempotent
 * on {@code (world_id, event_cursor)} via {@code ON CONFLICT DO NOTHING}, and
 * checkpoint retention keeps the latest ten rows per world.
 */
public final class PostgresWorldStore implements WorldStorePort {

    private final Jdbi jdbi;

    public PostgresWorldStore(Jdbi jdbi) {
        this.jdbi = Objects.requireNonNull(jdbi, "jdbi");
    }

    // ------------------------------------------------------------------ seat metadata

    @Override
    public SeatRecord createSeat(UserId owner, String seatName, ControllerKind kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        String trimmed = seatName == null ? "" : seatName.trim();
        if (trimmed.length() < 3 || trimmed.length() > 40) {
            throw new IllegalArgumentException(
                    "seat name must be 3..40 characters after trim: \"" + trimmed + "\"");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.isISOControl(trimmed.charAt(i))) {
                throw new IllegalArgumentException(
                        "seat name must not contain ISO control characters");
            }
        }
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

    @Override
    public WorldRecord ensureActiveWorld(long worldSeed, int rulesVersion, int engineVersion,
            int protocolVersion) {
        return jdbi.inTransaction(handle -> {
            Optional<WorldRecord> active = selectActiveWorld(handle);
            if (active.isPresent()) {
                WorldRecord world = active.get();
                if (world.worldSeed() != worldSeed || world.rulesVersion() != rulesVersion
                        || world.engineVersion() != engineVersion
                        || world.protocolVersion() != protocolVersion) {
                    throw new IllegalStateException("active world version triple mismatch: stored "
                            + world.worldSeed() + "/" + world.rulesVersion() + "/"
                            + world.engineVersion() + "/" + world.protocolVersion()
                            + " requested " + worldSeed + "/" + rulesVersion + "/"
                            + engineVersion + "/" + protocolVersion);
                }
                return world;
            }
            return handle.select("INSERT INTO wilds_meta.worlds (title, world_seed,"
                            + " rules_version, engine_version, protocol_version, jvm_version)"
                            + " VALUES (?, ?, ?, ?, ?, ?) RETURNING world_id, world_seed,"
                            + " rules_version, engine_version, protocol_version",
                    "Ironwood Wilds", worldSeed, rulesVersion, engineVersion, protocolVersion,
                    Runtime.version().toString())
                    .map((rs, ctx) -> worldRow(rs))
                    .one();
        });
    }

    private static WorldRecord worldRow(java.sql.ResultSet rs) throws SQLException {
        return new WorldRecord(rs.getInt("world_id"), rs.getLong("world_seed"),
                rs.getInt("rules_version"), rs.getInt("engine_version"),
                rs.getInt("protocol_version"), WorldRecord.ACTIVE);
    }

    private static Optional<WorldRecord> selectActiveWorld(org.jdbi.v3.core.Handle handle) {
        return handle.select("SELECT world_id, world_seed, rules_version, engine_version,"
                        + " protocol_version FROM wilds_meta.worlds WHERE status = 'ACTIVE'"
                        + " ORDER BY world_id DESC LIMIT 1")
                .map((rs, ctx) -> worldRow(rs))
                .findFirst();
    }

    // ------------------------------------------------------------------ command WAL

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
        return jdbi.withHandle(handle -> handle.select(
                        "INSERT INTO wilds_world.command_wal (world_id, admitted_ms, cmd_type,"
                                + " payload_json, world_hash) VALUES (?, ?, 'CLOCK_ADVANCE',"
                                + " '{}'::jsonb, ?) RETURNING wal_seq",
                        worldId, admittedMs, worldHash)
                .mapTo(long.class).one());
    }

    @Override
    public long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId,
            WorldCommand cmd, Long worldHash) {
        try {
            return jdbi.withHandle(handle -> handle.select(
                            "INSERT INTO wilds_world.command_wal (world_id, admitted_ms, seat_id,"
                                    + " cmd_id, cmd_type, payload_json, world_hash)"
                                    + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING wal_seq",
                            worldId, admittedMs, seatId, cmdId, cmdType(cmd),
                            CommandCodec.encode(cmd), worldHash)
                    .mapTo(long.class).one());
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
        jdbi.useHandle(handle -> {
            for (StoredWorldEvent event : events) {
                handle.execute("INSERT INTO wilds_world.world_events (world_id, event_cursor,"
                                + " occurred_ms, kind, seat_id, public_summary, private_summary,"
                                + " payload_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)"
                                + " ON CONFLICT (world_id, event_cursor) DO NOTHING",
                        worldId, event.cursor(), event.occurredMs(), event.kind(),
                        event.seatId(), event.publicSummary(), event.privateSummary(),
                        WorldEventPayloadCodec.encode(event.event()));
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
                .map((rs, ctx) -> {
                    long cursor = rs.getLong("event_cursor");
                    long occurredMs = rs.getLong("occurred_ms");
                    String kind = rs.getString("kind");
                    String publicSummary = rs.getString("public_summary");
                    String privateSummary = rs.getString("private_summary");
                    if (seatIdOrNullForPublic != null) {
                        return StoredWorldEvent.full(cursor, occurredMs, kind,
                                rs.getLong("seat_id"), publicSummary, privateSummary,
                                WorldEventPayloadCodec.decode(kind, cursor, occurredMs,
                                        publicSummary, privateSummary, rs.getString("payload")));
                    }
                    return StoredWorldEvent.publicProjection(new StoredWorldEvent(cursor,
                            occurredMs, kind, null, publicSummary, null, null));
                })
                .list());
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
        return jdbi.withHandle(handle -> handle.select(
                        "SELECT wal_seq, admitted_ms, seat_id, cmd_id::text AS cmd_id, cmd_type,"
                                + " payload_json::text AS payload, world_hash"
                                + " FROM wilds_world.command_wal WHERE world_id = ?"
                                + " AND wal_seq > ? ORDER BY wal_seq",
                        worldId, afterWalSeq)
                .map((rs, ctx) -> {
                    long walSeq = rs.getLong("wal_seq");
                    long admittedMs = rs.getLong("admitted_ms");
                    Long worldHash = rs.getObject("world_hash", Long.class);
                    if ("CLOCK_ADVANCE".equals(rs.getString("cmd_type"))) {
                        return (WalRecord) new WalRecord.ClockAdvance(walSeq, admittedMs,
                                worldHash);
                    }
                    return (WalRecord) new WalRecord.CommandEntry(walSeq, admittedMs,
                            rs.getLong("seat_id"), rs.getString("cmd_id"),
                            CommandCodec.decode(rs.getString("payload")), worldHash);
                })
                .list());
    }

    @Override
    public void exportWalJsonl(int worldId, Writer out) throws IOException {
        WorldRecord world = jdbi.withHandle(handle -> handle.select(
                        "SELECT world_id, world_seed, rules_version, engine_version,"
                                + " protocol_version FROM wilds_meta.worlds WHERE world_id = ?",
                        worldId)
                .map((rs, ctx) -> worldRow(rs))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no world row to export: " + worldId)));
        out.write(WalJsonlCodec.headerLine(world.worldSeed(), world.rulesVersion(),
                world.engineVersion()));
        out.write('\n');
        for (WalRecord record : loadWalAfter(worldId, 0L)) {
            out.write(CommandCodec.encodeWal(record));
            out.write('\n');
        }
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
