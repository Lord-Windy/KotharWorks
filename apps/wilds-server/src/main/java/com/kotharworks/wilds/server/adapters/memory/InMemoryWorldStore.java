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
import com.kotharworks.wilds.server.ports.WalJsonlCodec;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * Thread-safe in-memory reference {@link WorldStorePort}: seats with unique
 * names, one active world, an append-only WAL with strictly pre-mutation
 * parameters, an idempotent event log, and single-slot checkpoints. A test
 * fault hook can make {@code appendCommand} throw to prove
 * Commit-Before-Mutate leaves the engine untouched. Nothing here is durable —
 * the Postgres implementation (Ticket 08) replaces it shape-for-shape.
 */
public final class InMemoryWorldStore implements WorldStorePort {

    private final AtomicLong seatIds = new AtomicLong(1);
    private final AtomicLong walSeqs = new AtomicLong(1);
    private final AtomicLong checkpointIds = new AtomicLong(1);
    private final Object lock = new Object();

    private final Map<Long, SeatRecord> seatsById = new LinkedHashMap<>();
    private final Set<String> seatNames = new HashSet<>();
    private WorldRecord world;
    private final Map<Integer, List<WalRecord>> walByWorld = new HashMap<>();
    private final Map<Integer, Set<String>> cmdIdsByWorld = new HashMap<>();
    private final Map<Integer, TreeMap<Long, StoredWorldEvent>> eventsByWorld = new HashMap<>();
    private final Map<Integer, StoredCheckpoint> checkpointsByWorld = new HashMap<>();

    /** Test fault injection: when non-null, {@code appendCommand} throws it. */
    private volatile RuntimeException appendCommandFault;

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
    public WorldRecord ensureActiveWorld(long worldSeed, int rulesVersion, int engineVersion,
            int protocolVersion) {
        synchronized (lock) {
            if (world == null) {
                world = new WorldRecord(1, worldSeed, rulesVersion, engineVersion, protocolVersion,
                        WorldRecord.ACTIVE);
                walByWorld.put(world.worldId(), new ArrayList<>());
                cmdIdsByWorld.put(world.worldId(), new HashSet<>());
                eventsByWorld.put(world.worldId(), new TreeMap<>());
                return world;
            }
            if (world.worldSeed() != worldSeed || world.rulesVersion() != rulesVersion
                    || world.engineVersion() != engineVersion
                    || world.protocolVersion() != protocolVersion) {
                throw new IllegalStateException("active world version triple mismatch: stored "
                        + world.worldSeed() + "/" + world.rulesVersion() + "/"
                        + world.engineVersion() + "/" + world.protocolVersion()
                        + " requested " + worldSeed + "/" + rulesVersion + "/" + engineVersion
                        + "/" + protocolVersion);
            }
            return world;
        }
    }

    @Override
    public boolean existsByCmdId(int worldId, UUID cmdId) {
        synchronized (lock) {
            return cmdIdsByWorld.getOrDefault(worldId, Set.of()).contains(cmdId.toString());
        }
    }

    @Override
    public long appendClockAdvance(int worldId, long admittedMs, Long worldHash) {
        synchronized (lock) {
            requireWorld(worldId);
            long walSeq = walSeqs.getAndIncrement();
            walByWorld.get(worldId).add(new WalRecord.ClockAdvance(walSeq, admittedMs, worldHash));
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
            if (!cmdIdsByWorld.get(worldId).add(cmdId.toString())) {
                throw new IllegalStateException("duplicate cmdId in WAL: " + cmdId);
            }
            long walSeq = walSeqs.getAndIncrement();
            walByWorld.get(worldId).add(new WalRecord.CommandEntry(walSeq, admittedMs, seatId,
                    cmdId.toString(), cmd, worldHash));
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
            for (StoredWorldEvent row : log.tailMap(sinceCursor + 1).values()) {
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
            requireWorld(worldId);
            out.write(WalJsonlCodec.headerLine(world.worldSeed(), world.rulesVersion(),
                    world.engineVersion()));
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
        if (world == null || world.worldId() != worldId) {
            throw new IllegalArgumentException("unknown world: " + worldId);
        }
    }
}
