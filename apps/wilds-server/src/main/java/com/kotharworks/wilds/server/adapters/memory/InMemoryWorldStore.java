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
package com.kotharworks.wilds.server.adapters.memory;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.replay.CommandCodec;
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

/**
 * Thread-safe in-memory reference {@link WorldStorePort}: seats with unique
 * names, at most one active world (archived worlds keep their logs and stay
 * exportable), an append-only WAL with strictly pre-mutation
 * parameters, an idempotent event log, and single-slot checkpoints. A test
 * fault hook can make {@code appendCommand} throw to prove
 * Commit-Before-Mutate leaves the engine untouched. Nothing here is durable —
 * the Postgres implementation (Ticket 08) replaces it shape-for-shape.
 */
public final class InMemoryWorldStore implements WorldStorePort {

    private final AtomicLong seatIds = new AtomicLong(1);
    private final AtomicLong checkpointIds = new AtomicLong(1);
    private final Object lock = new Object();

    private final Map<Long, SeatRecord> seatsById = new LinkedHashMap<>();
    private final Set<String> seatNames = new HashSet<>();
    private final TreeMap<Integer, WorldRecord> worlds = new TreeMap<>();
    private int activeWorldId;
    private final Map<Integer, List<WalRecord>> walByWorld = new HashMap<>();
    private final Map<Integer, Map<String, WalRecord.CommandEntry>> cmdEntriesByWorld = new HashMap<>();
    private final Map<Integer, TreeMap<Long, StoredWorldEvent>> eventsByWorld = new HashMap<>();
    private final Map<Integer, StoredCheckpoint> checkpointsByWorld = new HashMap<>();

    /** Test fault injection: when non-null, {@code appendCommand} throws it. */
    private volatile RuntimeException appendCommandFault;

    @Override
    public SeatRecord createSeat(UserId owner, String seatName, ControllerKind kind) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(kind, "kind");
        String trimmed = PrintableText.validateSeatName(seatName);
        synchronized (lock) {
            if (!seatNames.add(trimmed)) {
                throw new IllegalArgumentException("DUPLICATE_SEAT: \"" + trimmed
                        + "\" is already chartered");
            }
            SeatRecord seat = new SeatRecord(seatIds.getAndIncrement(), owner, trimmed, kind);
            seatsById.put(seat.seatId(), seat);
            return seat;
        }
    }

    @Override
    public List<SeatRecord> findSeatsByOwner(UserId owner) {
        synchronized (lock) {
            return seatsById.values().stream()
                    .filter(seat -> seat.ownerUserId().equals(owner))
                    .sorted(Comparator.comparingLong(SeatRecord::seatId))
                    .toList();
        }
    }

    @Override
    public List<SeatRecord> findAllSeats() {
        synchronized (lock) {
            return seatsById.values().stream()
                    .sorted(Comparator.comparingLong(SeatRecord::seatId))
                    .toList();
        }
    }

    @Override
    public Optional<SeatRecord> findSeatById(long seatId) {
        synchronized (lock) {
            return Optional.ofNullable(seatsById.get(seatId));
        }
    }

    @Override
    public ActiveWorld ensureActiveWorld(long worldSeed, int rulesVersion, int engineVersion,
            int protocolVersion, long rulesFingerprint, OnVersionMismatch onMismatch) {
        Objects.requireNonNull(onMismatch, "onMismatch");
        Pin running = new Pin(worldSeed, rulesVersion, engineVersion, protocolVersion,
                rulesFingerprint);
        synchronized (lock) {
            WorldRecord active = worlds.get(activeWorldId);
            if (active == null) {
                return new ActiveWorld(createWorld(running), null);
            }
            Pin stored = Pin.of(active);
            if (stored.admits(running)) {
                return new ActiveWorld(active, null);
            }
            return switch (onMismatch) {
                case REFUSE -> throw new WorldVersionMismatchException(active.worldId(), stored,
                        running);
                case ARCHIVE -> {
                    WorldRecord archived = new WorldRecord(active.worldId(), active.worldSeed(),
                            active.rulesVersion(), active.engineVersion(),
                            active.protocolVersion(), active.rulesFingerprint(),
                            WorldRecord.ARCHIVED);
                    worlds.put(archived.worldId(), archived);
                    yield new ActiveWorld(createWorld(running), archived);
                }
            };
        }
    }

    /** Inserts a fresh ACTIVE world with id max+1 and empty per-world logs (caller holds lock). */
    private WorldRecord createWorld(Pin pin) {
        int worldId = worlds.isEmpty() ? 1 : worlds.lastKey() + 1;
        WorldRecord created = new WorldRecord(worldId, pin.worldSeed(), pin.rulesVersion(),
                pin.engineVersion(), pin.protocolVersion(), pin.rulesFingerprint(),
                WorldRecord.ACTIVE);
        worlds.put(worldId, created);
        activeWorldId = worldId;
        walByWorld.put(worldId, new ArrayList<>());
        cmdEntriesByWorld.put(worldId, new HashMap<>());
        eventsByWorld.put(worldId, new TreeMap<>());
        return created;
    }

    @Override
    public Optional<WalRecord.CommandEntry> findCommandByCmdId(int worldId, UUID cmdId) {
        Objects.requireNonNull(cmdId, "cmdId");
        synchronized (lock) {
            return Optional.ofNullable(
                    cmdEntriesByWorld.getOrDefault(worldId, Map.of()).get(cmdId.toString()));
        }
    }

    @Override
    public boolean existsByCmdId(int worldId, UUID cmdId) {
        return findCommandByCmdId(worldId, cmdId).isPresent();
    }

    @Override
    public long appendClockAdvance(int worldId, long admittedMs, Long worldHash) {
        synchronized (lock) {
            requireWorld(worldId);
            List<WalRecord> wal = walByWorld.get(worldId);
            long walSeq = wal.size() + 1L;
            wal.add(new WalRecord.ClockAdvance(walSeq, admittedMs, worldHash));
            return walSeq;
        }
    }

    @Override
    public long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId,
            WorldCommand cmd, Long worldHash) {
        RuntimeException fault = appendCommandFault;
        if (fault != null) {
            throw fault;
        }
        synchronized (lock) {
            requireWorld(worldId);
            Map<String, WalRecord.CommandEntry> byCmdId = cmdEntriesByWorld.get(worldId);
            if (byCmdId.containsKey(cmdId.toString())) {
                throw new IllegalStateException("duplicate cmdId in WAL: " + cmdId);
            }
            List<WalRecord> wal = walByWorld.get(worldId);
            long walSeq = wal.size() + 1L;
            WalRecord.CommandEntry entry = new WalRecord.CommandEntry(walSeq, admittedMs, seatId,
                    cmdId.toString(), cmd, worldHash);
            byCmdId.put(cmdId.toString(), entry);
            wal.add(entry);
            return walSeq;
        }
    }

    @Override
    public void appendEvents(int worldId, List<StoredWorldEvent> events) {
        synchronized (lock) {
            requireWorld(worldId);
            TreeMap<Long, StoredWorldEvent> log = eventsByWorld.get(worldId);
            for (StoredWorldEvent event : events) {
                log.putIfAbsent(event.cursor(), event);
            }
        }
    }

    @Override
    public List<StoredWorldEvent> readEventsSince(int worldId, Long seatIdOrNullForPublic,
            long sinceCursor, int limit) {
        synchronized (lock) {
            requireWorld(worldId);
            TreeMap<Long, StoredWorldEvent> log = eventsByWorld.get(worldId);
            List<StoredWorldEvent> result = new ArrayList<>(Math.min(limit, 64));
            for (StoredWorldEvent row : log.tailMap(sinceCursor, false).values()) {
                if (result.size() >= limit) {
                    break;
                }
                if (seatIdOrNullForPublic != null) {
                    if (!seatIdOrNullForPublic.equals(row.seatId())) {
                        continue;
                    }
                    result.add(row);
                } else if (row.publicSummary() != null) {
                    result.add(StoredWorldEvent.publicProjection(row));
                }
            }
            return List.copyOf(result);
        }
    }

    @Override
    public List<StoredWorldEvent> readRecentEvents(int worldId, Long seatIdOrNullForPublic,
            int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1, got: " + limit);
        }
        synchronized (lock) {
            requireWorld(worldId);
            TreeMap<Long, StoredWorldEvent> log = eventsByWorld.get(worldId);
            List<StoredWorldEvent> reversed = new ArrayList<>(Math.min(limit, 64));
            for (StoredWorldEvent row : log.descendingMap().values()) {
                if (reversed.size() >= limit) {
                    break;
                }
                if (seatIdOrNullForPublic != null) {
                    if (!seatIdOrNullForPublic.equals(row.seatId())) {
                        continue;
                    }
                    reversed.add(row);
                } else if (row.publicSummary() != null) {
                    reversed.add(StoredWorldEvent.publicProjection(row));
                }
            }
            Collections.reverse(reversed);
            return List.copyOf(reversed);
        }
    }

    @Override
    public void saveCheckpoint(int worldId, long upToWalSeq, long logicalMs, long worldHash,
            String stateJson) {
        synchronized (lock) {
            requireWorld(worldId);
            checkpointsByWorld.put(worldId, new StoredCheckpoint(
                    checkpointIds.getAndIncrement(), upToWalSeq, logicalMs, worldHash, stateJson));
        }
    }

    @Override
    public Optional<StoredCheckpoint> loadLatestCheckpoint(int worldId) {
        synchronized (lock) {
            return Optional.ofNullable(checkpointsByWorld.get(worldId));
        }
    }

    @Override
    public List<WalRecord> loadWalAfter(int worldId, long afterWalSeq) {
        synchronized (lock) {
            requireWorld(worldId);
            List<WalRecord> tail = new ArrayList<>();
            for (WalRecord record : walByWorld.get(worldId)) {
                long seq = switch (record) {
                    case WalRecord.ClockAdvance advance -> advance.walSeq();
                    case WalRecord.CommandEntry entry -> entry.walSeq();
                };
                if (seq > afterWalSeq) {
                    tail.add(record);
                }
            }
            return List.copyOf(tail);
        }
    }

    @Override
    public void exportWalJsonl(int worldId, Writer out) throws IOException {
        synchronized (lock) {
            WorldRecord world = worlds.get(worldId);
            if (world == null) {
                throw new IllegalStateException("no world row to export: " + worldId);
            }
            out.write(new WalHeader(world.worldSeed(), world.rulesVersion(),
                    world.engineVersion(), world.rulesFingerprint()).encode());
            out.write('\n');
            for (WalRecord record : walByWorld.get(worldId)) {
                out.write(CommandCodec.encodeWal(record));
                out.write('\n');
            }
        }
    }

    // ------------------------------------------------------------------ test hooks

    /** Arms (or clears, with {@code null}) the {@code appendCommand} write fault. */
    public void setAppendCommandFault(RuntimeException fault) {
        this.appendCommandFault = fault;
    }

    /** Total WAL rows across all worlds (test assertions). */
    public int totalWalRows() {
        synchronized (lock) {
            return walByWorld.values().stream().mapToInt(List::size).sum();
        }
    }

    /** Stored event rows for a world (test assertions). */
    public int eventCount(int worldId) {
        synchronized (lock) {
            return eventsByWorld.getOrDefault(worldId, new TreeMap<>()).size();
        }
    }

    private void requireWorld(int worldId) {
        if (!worlds.containsKey(worldId)) {
            throw new IllegalArgumentException("unknown world: " + worldId);
        }
    }
}
