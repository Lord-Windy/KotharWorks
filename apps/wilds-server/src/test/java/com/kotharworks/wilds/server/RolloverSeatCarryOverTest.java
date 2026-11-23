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
package com.kotharworks.wilds.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.kotharworks.auth.principal.UserId;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.adapters.memory.InMemoryEventBus;
import com.kotharworks.wilds.server.adapters.memory.InMemoryWorldStore;
import com.kotharworks.wilds.server.domain.CommandOutcome;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.SeatCharter;
import com.kotharworks.wilds.server.domain.WorldHost;
import com.kotharworks.wilds.server.ports.ActiveWorld;
import com.kotharworks.wilds.server.ports.ClockPort;
import com.kotharworks.wilds.server.ports.OnVersionMismatch;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * Ticket 11 §3.6 seat carry-over (Review Focus 7): after an archive rollover
 * the {@link WorldHost} constructor re-registers every seat through its
 * mailbox, one Commit-Before-Mutate WAL row per seat in ascending
 * {@code seatId} order (whatever order the adapter lists them in), with the
 * charter route's {@link SeatCharter} naming and {@code cmdId}; one failing
 * seat is logged and never aborts boot, and a non-rollover boot of a fully
 * owned world submits nothing. Any boot registers seats left without an
 * owner, so an interrupted carry-over resumes (review finding 11-M1).
 */
@Timeout(60)
class RolloverSeatCarryOverTest {

    private final List<LogRecord> logs = new CopyOnWriteArrayList<>();
    private final Logger hostLogger = Logger.getLogger(WorldHost.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
            logs.add(logRecord);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void captureHostLogs() {
        hostLogger.addHandler(capture);
    }

    @AfterEach
    void releaseHostLogs() {
        hostLogger.removeHandler(capture);
    }

    @Test
    void rolloverReRegistersEverySeatThroughTheMailboxInSeatIdOrderAndOnlyOnce() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        List<SeatRecord> seats = bootWorldAWithTwoRegisteredSeats(store);
        int worldA = 1;

        // The adapter lists seats in DESCENDING order: the host must sort them itself.
        ScriptedStore reversed = new ScriptedStore(store);
        reversed.reverseSeats = true;
        WorldHost rolled = boot(reversed, 2L, OnVersionMismatch.ARCHIVE);
        int worldB;
        try {
            assertEquals(worldA, rolled.archivedWorld().orElseThrow().worldId());
            worldB = rolled.worldId();
            // No waiting: the constructor returns only after every seat was processed.
            List<WalRecord.CommandEntry> registrations = registerOwners(store, worldB);
            assertEquals(2, registrations.size(), "one RegisterOwner per seat");
            List<WalRecord> walB = store.loadWalAfter(worldB, 0L);
            assertEquals(registrations, walB.subList(0, 2),
                    "world B's WAL begins with the seat registrations");
            for (int i = 0; i < seats.size(); i++) {
                SeatRecord seat = seats.get(i);
                WalRecord.CommandEntry entry = registrations.get(i);
                assertEquals(seat.seatId(), entry.seatId(), "ascending seatId order");
                assertEquals(SeatCharter.registerCmdId(worldB, seat.seatId()).toString(),
                        entry.cmdId(), "the charter route's deterministic cmdId");
                assertEquals(new WorldCommand.RegisterOwner(seat.seatId(), seat.seatName(),
                        SeatCharter.defaultActorLabel(seat.seatName())), entry.command());
                assertTrue(rolled.view().findOwnerBySeatKey(seat.seatId()).isPresent(),
                        "seat " + seat.seatId() + " owns in world B");
            }
            assertTrue(registrations.get(0).walSeq() < registrations.get(1).walSeq());
            assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.INFO
                    && formatted(r).contains("re-registered 2 of 2 seats into world_id "
                            + worldB)), "INFO summary line: " + messages());

            // A later charter retry for the same seat dedupes on the committed row.
            SeatRecord first = seats.getFirst();
            CommandOutcome.Ok retry = assertInstanceOf(CommandOutcome.Ok.class,
                    rolled.submit(first.seatId(), SeatCharter.registerCmdId(worldB,
                            first.seatId()), new WorldCommand.RegisterOwner(first.seatId(),
                            first.seatName(), SeatCharter.defaultActorLabel(first.seatName()))));
            assertEquals(registrations.getFirst().walSeq(), retry.receipt().walSeq());
            assertEquals(2, registerOwners(store, worldB).size(), "the retry did not re-run");
        } finally {
            rolled.close();
        }

        // (d) A matching (non-rollover) reboot of world B submits nothing.
        logs.clear();
        WorldHost matching = boot(store, 2L, OnVersionMismatch.REFUSE);
        try {
            assertTrue(matching.archivedWorld().isEmpty());
            assertEquals(worldB, matching.worldId());
            assertEquals(2, registerOwners(store, worldB).size(),
                    "a non-rollover boot appends no RegisterOwner");
            for (SeatRecord seat : seats) {
                assertTrue(matching.view().findOwnerBySeatKey(seat.seatId()).isPresent());
            }
            assertFalse(logs.stream().anyMatch(r -> formatted(r).contains("re-registered")),
                    "no carry-over on a matching boot: " + messages());
        } finally {
            matching.close();
        }
        assertEquals(2, registerOwners(store, worldA).size(),
                "world A's own registrations are untouched by the rollover");
    }

    @Test
    void oneFailingSeatIsLoggedAndNeverAbortsBootAndItsRetrySucceeds() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        List<SeatRecord> seats = bootWorldAWithTwoRegisteredSeats(store);
        SeatRecord failing = seats.get(0);
        SeatRecord healthy = seats.get(1);
        int worldB = 2;
        UUID failingCmdId = SeatCharter.registerCmdId(worldB, failing.seatId());

        ScriptedStore faulty = new ScriptedStore(store);
        faulty.failOnceCmdId = failingCmdId;
        WorldHost rolled = boot(faulty, 2L, OnVersionMismatch.ARCHIVE);
        try {
            assertEquals(worldB, rolled.worldId());
            assertNull(faulty.failOnceCmdId, "the fault fired during boot");
            List<WalRecord.CommandEntry> registrations = registerOwners(store, worldB);
            assertEquals(1, registrations.size(), "only the healthy seat registered");
            assertEquals(healthy.seatId(), registrations.getFirst().seatId());
            assertTrue(rolled.view().findOwnerBySeatKey(healthy.seatId()).isPresent());
            assertTrue(rolled.view().findOwnerBySeatKey(failing.seatId()).isEmpty(),
                    "the failed seat stays unregistered (retry-registration path)");
            assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                    && formatted(r).contains("seat " + failing.seatId())
                    && formatted(r).contains("injected append failure")),
                    "WARNING names the seat and the reason: " + messages());
            assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.INFO
                    && formatted(r).contains("re-registered 1 of 2 seats into world_id "
                            + worldB)), "INFO summary line: " + messages());

            // The /play retry path: same cmdId, now succeeds.
            assertInstanceOf(CommandOutcome.Ok.class, rolled.submit(failing.seatId(),
                    failingCmdId, new WorldCommand.RegisterOwner(failing.seatId(),
                            failing.seatName(), SeatCharter.defaultActorLabel(
                                    failing.seatName()))));
            assertTrue(rolled.view().findOwnerBySeatKey(failing.seatId()).isPresent());
            assertEquals(2, registerOwners(store, worldB).size());
        } finally {
            rolled.close();
        }
    }

    @Test
    void seatListingFailureIsLoggedAndBootContinues() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        bootWorldAWithTwoRegisteredSeats(store);
        ScriptedStore broken = new ScriptedStore(store);
        broken.failFindAllSeats = true;
        WorldHost rolled = boot(broken, 2L, OnVersionMismatch.ARCHIVE);
        try {
            assertTrue(rolled.archivedWorld().isPresent());
            assertEquals(0, registerOwners(store, rolled.worldId()).size());
            assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                    && formatted(r).contains("could not list seats")
                    && formatted(r).contains("injected listing failure")), messages());
        } finally {
            rolled.close();
        }
    }

    /**
     * Review finding 11-M1: the archive transaction commits, then the process dies
     * before the carry-over loop. The next boot with the same command line sees a
     * world that already matches the running pin (no rollover), and must still
     * finish the carry-over instead of leaving every seat ownerless for good.
     */
    @Test
    void carryOverInterruptedByACrashAfterTheArchiveCommitResumesOnTheNextBoot() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        List<SeatRecord> seats = bootWorldAWithTwoRegisteredSeats(store);

        // The archive commit lands, then the process dies before any WorldHost runs.
        ActiveWorld crashed = store.ensureActiveWorld(2L,
                WorldHost.runningPin(2L).rulesVersion(), WorldHost.runningPin(2L).engineVersion(),
                WorldHost.runningPin(2L).protocolVersion(),
                WorldHost.runningPin(2L).rulesFingerprint(), OnVersionMismatch.ARCHIVE);
        assertTrue(crashed.rolledOver());
        int worldB = crashed.world().worldId();
        assertEquals(0, registerOwners(store, worldB).size(), "nothing carried over yet");

        logs.clear();
        WorldHost restarted = boot(store, 2L, OnVersionMismatch.ARCHIVE);
        try {
            assertEquals(worldB, restarted.worldId());
            assertTrue(restarted.archivedWorld().isEmpty(), "no rollover on the restart");
            List<WalRecord.CommandEntry> registrations = registerOwners(store, worldB);
            assertEquals(2, registrations.size(), "the carry-over finished on restart");
            for (int i = 0; i < seats.size(); i++) {
                SeatRecord seat = seats.get(i);
                assertEquals(seat.seatId(), registrations.get(i).seatId(),
                        "ascending seatId order");
                assertEquals(SeatCharter.registerCmdId(worldB, seat.seatId()).toString(),
                        registrations.get(i).cmdId());
                assertTrue(restarted.view().findOwnerBySeatKey(seat.seatId()).isPresent(),
                        "seat " + seat.seatId() + " owns in world B");
            }
            assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                    && formatted(r).contains("2 seat(s) with no owner")),
                    "WARNING names the resumed carry-over: " + messages());
        } finally {
            restarted.close();
        }

        // Once complete, a further boot is a no-op.
        logs.clear();
        WorldHost again = boot(store, 2L, OnVersionMismatch.REFUSE);
        try {
            assertEquals(2, registerOwners(store, worldB).size(), "idempotent once complete");
            assertFalse(logs.stream().anyMatch(r -> formatted(r).contains("re-registered")),
                    "a fully owned world submits and logs nothing: " + messages());
        } finally {
            again.close();
        }
    }

    @Test
    void aPartialCarryOverRegistersOnlyTheMissingSeats() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        List<SeatRecord> seats = bootWorldAWithTwoRegisteredSeats(store);
        SeatRecord done = seats.get(0);
        SeatRecord missing = seats.get(1);

        // Rollover boot where the second seat's append fails (stands in for dying mid-loop).
        ScriptedStore faulty = new ScriptedStore(store);
        int worldB = 2;
        faulty.failOnceCmdId = SeatCharter.registerCmdId(worldB, missing.seatId());
        boot(faulty, 2L, OnVersionMismatch.ARCHIVE).close();
        assertEquals(List.of(done.seatId()), registerOwners(store, worldB).stream()
                .map(WalRecord.CommandEntry::seatId).toList());

        logs.clear();
        WorldHost restarted = boot(store, 2L, OnVersionMismatch.REFUSE);
        try {
            List<WalRecord.CommandEntry> registrations = registerOwners(store, worldB);
            assertEquals(List.of(done.seatId(), missing.seatId()), registrations.stream()
                    .map(WalRecord.CommandEntry::seatId).toList(), "only the gap was filled");
            assertTrue(restarted.view().findOwnerBySeatKey(missing.seatId()).isPresent());
            assertTrue(logs.stream().anyMatch(r -> r.getLevel() == Level.INFO
                    && formatted(r).contains("re-registered 1 of 1 seats into world_id "
                            + worldB)), messages());
        } finally {
            restarted.close();
        }
    }

    @Test
    void charterNamingIsTheRoutesContract() {
        assertEquals("Alice House's Actor", SeatCharter.defaultActorLabel("Alice House"));
        assertEquals(UUID.nameUUIDFromBytes("seat-register:7:42".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)), SeatCharter.registerCmdId(7, 42L));
    }

    // ------------------------------------------------------------------ rig

    /** Boots world A (seed 1), charters two seats through the mailbox, and closes it. */
    private static List<SeatRecord> bootWorldAWithTwoRegisteredSeats(InMemoryWorldStore store) {
        WorldHost hostA = boot(store, 1L, OnVersionMismatch.REFUSE);
        List<SeatRecord> seats = new ArrayList<>();
        try {
            assertEquals(1, hostA.worldId());
            for (String name : List.of("Alice House", "Bob House")) {
                SeatRecord seat = store.createSeat(new UserId(UUID.randomUUID()), name,
                        ControllerKind.HUMAN);
                seats.add(seat);
                assertInstanceOf(CommandOutcome.Ok.class, hostA.submit(seat.seatId(),
                        SeatCharter.registerCmdId(hostA.worldId(), seat.seatId()),
                        new WorldCommand.RegisterOwner(seat.seatId(), seat.seatName(),
                                "Custom " + name)));
            }
        } finally {
            hostA.close();
        }
        assertTrue(seats.get(0).seatId() < seats.get(1).seatId());
        return List.copyOf(seats);
    }

    private static WorldHost boot(WorldStorePort store, long seed, OnVersionMismatch policy) {
        return new WorldHost(store, new InMemoryEventBus(), ClockPort.SYSTEM, seed, 1L,
                WorldHost.DEFAULT_QUIET_ADVANCE_MS, Long.MAX_VALUE, policy);
    }

    private static List<WalRecord.CommandEntry> registerOwners(WorldStorePort store,
            int worldId) {
        return store.loadWalAfter(worldId, 0L).stream()
                .filter(WalRecord.CommandEntry.class::isInstance)
                .map(WalRecord.CommandEntry.class::cast)
                .filter(entry -> entry.command() instanceof WorldCommand.RegisterOwner)
                .toList();
    }

    private static String formatted(LogRecord logRecord) {
        String message = logRecord.getMessage();
        Object[] params = logRecord.getParameters();
        return params == null ? message : java.text.MessageFormat.format(message, params);
    }

    private String messages() {
        return logs.stream().map(RolloverSeatCarryOverTest::formatted).toList().toString();
    }

    /** A delegating store with scripted faults for the carry-over path. */
    private static final class ScriptedStore implements WorldStorePort {

        private final WorldStorePort delegate;
        volatile boolean reverseSeats;
        volatile boolean failFindAllSeats;
        volatile UUID failOnceCmdId;

        ScriptedStore(WorldStorePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public SeatRecord createSeat(UserId owner, String seatName, ControllerKind kind) {
            return delegate.createSeat(owner, seatName, kind);
        }

        @Override
        public List<SeatRecord> findSeatsByOwner(UserId owner) {
            return delegate.findSeatsByOwner(owner);
        }

        @Override
        public List<SeatRecord> findAllSeats() {
            if (failFindAllSeats) {
                throw new IllegalStateException("injected listing failure");
            }
            List<SeatRecord> seats = new ArrayList<>(delegate.findAllSeats());
            if (reverseSeats) {
                Collections.reverse(seats);
            }
            return seats;
        }

        @Override
        public Optional<SeatRecord> findSeatById(long seatId) {
            return delegate.findSeatById(seatId);
        }

        @Override
        public ActiveWorld ensureActiveWorld(long worldSeed, int rulesVersion,
                int engineVersion, int protocolVersion, long rulesFingerprint,
                OnVersionMismatch onMismatch) {
            return delegate.ensureActiveWorld(worldSeed, rulesVersion, engineVersion,
                    protocolVersion, rulesFingerprint, onMismatch);
        }

        @Override
        public Optional<WalRecord.CommandEntry> findCommandByCmdId(int worldId, UUID cmdId) {
            return delegate.findCommandByCmdId(worldId, cmdId);
        }

        @Override
        public long appendClockAdvance(int worldId, long admittedMs, Long worldHash) {
            return delegate.appendClockAdvance(worldId, admittedMs, worldHash);
        }

        @Override
        public long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId,
                WorldCommand cmd, Long worldHash) {
            if (cmdId.equals(failOnceCmdId)) {
                failOnceCmdId = null;
                throw new IllegalStateException("injected append failure");
            }
            return delegate.appendCommand(worldId, admittedMs, seatId, cmdId, cmd, worldHash);
        }

        @Override
        public void appendEvents(int worldId, List<StoredWorldEvent> events) {
            delegate.appendEvents(worldId, events);
        }

        @Override
        public List<StoredWorldEvent> readEventsSince(int worldId, Long seatIdOrNullForPublic,
                long sinceCursor, int limit) {
            return delegate.readEventsSince(worldId, seatIdOrNullForPublic, sinceCursor, limit);
        }

        @Override
        public List<StoredWorldEvent> readRecentEvents(int worldId, Long seatIdOrNullForPublic,
                int limit) {
            return delegate.readRecentEvents(worldId, seatIdOrNullForPublic, limit);
        }

        @Override
        public void saveCheckpoint(int worldId, long upToWalSeq, long logicalMs, long worldHash,
                String stateJson) {
            delegate.saveCheckpoint(worldId, upToWalSeq, logicalMs, worldHash, stateJson);
        }

        @Override
        public Optional<StoredCheckpoint> loadLatestCheckpoint(int worldId) {
            return delegate.loadLatestCheckpoint(worldId);
        }

        @Override
        public List<WalRecord> loadWalAfter(int worldId, long afterWalSeq) {
            return delegate.loadWalAfter(worldId, afterWalSeq);
        }

        @Override
        public void exportWalJsonl(int worldId, Writer out) throws IOException {
            delegate.exportWalJsonl(worldId, out);
        }
    }
}
