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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kotharworks.auth.AuthRejectException;
import com.kotharworks.auth.principal.ExternalIdentity;
import com.kotharworks.auth.principal.UserId;
import com.kotharworks.auth.spi.ScopedTokenRecord;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.id.ActorId;
import com.kotharworks.wilds.id.OwnerId;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.server.adapters.memory.InMemoryAuthStore;
import com.kotharworks.wilds.server.adapters.memory.InMemoryWorldStore;
import com.kotharworks.wilds.server.domain.ControllerKind;
import com.kotharworks.wilds.server.domain.SeatRateLimiter;
import com.kotharworks.wilds.server.ports.SeatRecord;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import org.junit.jupiter.api.Test;

/**
 * Reference-store contract tests: seat-name validation (printable, no HTML
 * banning), idempotent event append, the public/private projection split,
 * {@code cmdId} existence, and checkpoint/WAL round-trips.
 */
class InMemoryStoresTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"),
            ZoneOffset.UTC);

    // ------------------------------------------------------------------ seats

    @Test
    void seatNamesAcceptPrintableHtmlSignificantCharacters() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        UserId owner = new UserId(UUID.randomUUID());
        SeatRecord seat = store.createSeat(owner, "  <Windy>&\"s Seat\"  ", ControllerKind.HUMAN);
        assertEquals("<Windy>&\"s Seat\"", seat.seatName()); // trimmed, escaping lives in HTML
        assertEquals(16, seat.seatName().length());
        assertEquals(owner, seat.ownerUserId());
    }

    @Test
    void seatNameLengthAndBlankAndControlRejected() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        UserId owner = new UserId(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(owner, "ab", ControllerKind.HUMAN));
        assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(owner, "x".repeat(41), ControllerKind.HUMAN));
        assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(owner, "   ", ControllerKind.HUMAN));
        assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(owner, "bad\nname", ControllerKind.HUMAN));
    }

    @Test
    void duplicateSeatNameRejected() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        UserId owner = new UserId(UUID.randomUUID());
        store.createSeat(owner, "Windward", ControllerKind.HUMAN);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> store.createSeat(new UserId(UUID.randomUUID()), "  Windward ",
                        ControllerKind.SCRIPT_BOT));
        assertTrue(e.getMessage().startsWith("DUPLICATE_SEAT"));
    }

    @Test
    void seatQueriesRoundTrip() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        UserId owner = new UserId(UUID.randomUUID());
        SeatRecord first = store.createSeat(owner, "First", ControllerKind.HUMAN);
        SeatRecord second = store.createSeat(owner, "Second", ControllerKind.SCRIPT_BOT);
        assertEquals(List.of(first, second), store.findSeatsByOwner(owner));
        assertEquals(Optional.of(first), store.findSeatById(first.seatId()));
        assertTrue(store.findSeatById(999).isEmpty());
    }

    // ------------------------------------------------------------------ world + WAL

    @Test
    void ensureActiveWorldCreatesOnceAndAssertsTriple() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        var world = store.ensureActiveWorld(7L, 1, 1, 1);
        assertEquals(1, world.worldId());
        assertEquals(7L, world.worldSeed());
        assertEquals(store.ensureActiveWorld(7L, 1, 1, 1), world);
        assertThrows(IllegalStateException.class,
                () -> store.ensureActiveWorld(8L, 1, 1, 1));
    }

    @Test
    void existsByCmdIdAndAppendCommandRejectDuplicates() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        UUID cmdId = UUID.randomUUID();
        assertFalse(store.existsByCmdId(worldId, cmdId));
        long walSeq = store.appendCommand(worldId, 10L, 3L, cmdId,
                new WorldCommand.RegisterOwner(3L, "Seat", "Actor"), null);
        assertTrue(walSeq >= 1);
        assertTrue(store.existsByCmdId(worldId, cmdId));
        assertThrows(IllegalStateException.class, () -> store.appendCommand(worldId, 11L, 3L,
                cmdId, new WorldCommand.RegisterOwner(3L, "Seat", "Actor"), null));
    }

    @Test
    void faultInjectionThrowsOnAppendCommand() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        store.setAppendCommandFault(new IllegalStateException("disk down"));
        assertThrows(IllegalStateException.class,
                () -> store.appendCommand(worldId, 1L, 1L, UUID.randomUUID(),
                        new WorldCommand.RegisterOwner(1L, "S", "A"), null));
        assertEquals(0, store.totalWalRows()); // the fault precedes any row
        store.setAppendCommandFault(null);
        store.appendCommand(worldId, 1L, 1L, UUID.randomUUID(),
                new WorldCommand.RegisterOwner(1L, "S", "A"), null);
        assertEquals(1, store.totalWalRows());
    }

    @Test
    void loadWalAfterReturnsTailAscending() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        store.appendClockAdvance(worldId, 5L, null);
        long second = store.appendCommand(worldId, 5L, 1L, UUID.randomUUID(),
                new WorldCommand.RegisterOwner(1L, "S", "A"), null);
        store.appendClockAdvance(worldId, 50L, null);
        List<WalRecord> tail = store.loadWalAfter(worldId, second);
        assertEquals(1, tail.size());
        assertEquals(WalRecord.ClockAdvance.class, tail.getFirst().getClass());
        assertEquals(50L, ((WalRecord.ClockAdvance) tail.getFirst()).admittedMs());
    }

    // ------------------------------------------------------------------ events

    @Test
    void appendEventsIsIdempotentOnCursor() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        StoredWorldEvent row = StoredWorldEvent.full(1L, 5L, "owner_registered", 1L,
                "pub", "priv", sampleEvent());
        store.appendEvents(worldId, List.of(row));
        store.appendEvents(worldId, List.of(row));
        assertEquals(1, store.eventCount(worldId));
    }

    @Test
    void publicProjectionCarriesNoPrivateFields() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        store.appendEvents(worldId, List.of(
                StoredWorldEvent.full(1L, 5L, "owner_registered", 1L, "pub-1", "priv-1",
                        sampleEvent()),
                StoredWorldEvent.full(2L, 6L, "task_started", 1L, null, "priv-2",
                        sampleEvent())));
        List<StoredWorldEvent> publicRows = store.readEventsSince(worldId, null, 0, 100);
        assertEquals(1, publicRows.size()); // only publicSummary != null rows
        StoredWorldEvent row = publicRows.getFirst();
        assertEquals("owner_registered", row.kind());
        assertNull(row.privateSummary()); // structurally stripped
        assertNull(row.event()); // structurally stripped
        assertNull(row.seatId());
        assertEquals("pub-1", row.publicSummary());
    }

    @Test
    void privateReadReturnsOnlyThatSeatsEventsAscending() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        store.appendEvents(worldId, List.of(
                StoredWorldEvent.full(1L, 5L, "task_started", 7L, null, "priv-a", sampleEvent()),
                StoredWorldEvent.full(2L, 6L, "task_started", 8L, null, "priv-b", sampleEvent()),
                StoredWorldEvent.full(3L, 7L, "task_batch_completed", 7L, null, "priv-c",
                        sampleEvent())));
        List<StoredWorldEvent> rows = store.readEventsSince(worldId, 7L, 0, 100);
        assertEquals(List.of(1L, 3L), rows.stream().map(StoredWorldEvent::cursor).toList());
        assertEquals("priv-a", rows.get(0).privateSummary());
        rows = store.readEventsSince(worldId, 7L, 1L, 100);
        assertEquals(List.of(3L), rows.stream().map(StoredWorldEvent::cursor).toList());
    }

    @Test
    void readEventsSinceHonorsLimitAndMaxCursorAndEventKindSanitization() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        store.appendEvents(worldId, List.of(
                StoredWorldEvent.full(1L, 1L, "task_started", 1L, null, "p", sampleEvent()),
                StoredWorldEvent.full(2L, 2L, "task_started", 1L, null, "p", sampleEvent())));
        assertEquals(1, store.readEventsSince(worldId, 1L, 0, 1).size());
        assertTrue(store.readEventsSince(worldId, 1L, Long.MAX_VALUE, 10).isEmpty(),
                "sinceCursor == Long.MAX_VALUE must not overflow into Long.MIN_VALUE");
        assertThrows(IllegalArgumentException.class,
                () -> StoredWorldEvent.full(1L, 1L, "bad\nkind", 1L, null, "p", sampleEvent()));
    }

    @Test
    void readRecentEventsReturnsNewestTailInAscendingCursorOrder() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(1L, 1, 1, 1).worldId();
        java.util.ArrayList<StoredWorldEvent> batch = new java.util.ArrayList<>();
        for (long c = 1; c <= 60; c++) {
            String pub = (c % 2 == 0) ? "pub-" + c : null;
            batch.add(StoredWorldEvent.full(c, c * 10L, "owner_registered", 1L,
                    pub, "priv-" + c, sampleEvent()));
        }
        store.appendEvents(worldId, batch);

        List<StoredWorldEvent> seatTail = store.readRecentEvents(worldId, 1L, 10);
        assertEquals(List.of(51L, 52L, 53L, 54L, 55L, 56L, 57L, 58L, 59L, 60L),
                seatTail.stream().map(StoredWorldEvent::cursor).toList());

        List<StoredWorldEvent> publicTail = store.readRecentEvents(worldId, null, 5);
        assertEquals(List.of(52L, 54L, 56L, 58L, 60L),
                publicTail.stream().map(StoredWorldEvent::cursor).toList());
        assertTrue(publicTail.stream().allMatch(row -> row.privateSummary() == null
                && row.event() == null && row.seatId() == null));
        assertThrows(IllegalArgumentException.class,
                () -> store.readRecentEvents(worldId, 1L, 0));
    }

    // ------------------------------------------------------------------ checkpoints

    @Test
    void checkpointRoundTripAndWalTail() {
        InMemoryWorldStore store = new InMemoryWorldStore();
        int worldId = store.ensureActiveWorld(3L, 1, 1, 1).worldId();
        assertTrue(store.loadLatestCheckpoint(worldId).isEmpty());
        store.saveCheckpoint(worldId, 5L, 500L, -88L, "{\"state\":1}");
        store.saveCheckpoint(worldId, 9L, 900L, -77L, "{\"state\":2}");
        Optional<StoredCheckpoint> checkpoint = store.loadLatestCheckpoint(worldId);
        assertEquals(9L, checkpoint.orElseThrow().upToWalSeq());
        assertEquals(900L, checkpoint.orElseThrow().logicalMs());
        assertEquals(-77L, checkpoint.orElseThrow().worldHash());
        assertEquals("{\"state\":2}", checkpoint.orElseThrow().stateJson());
    }

    // ------------------------------------------------------------------ auth store

    @Test
    void authStoreUpsertRefreshesDisplayNameAndSuspensionIsLive() {
        InMemoryAuthStore store = new InMemoryAuthStore(CLOCK);
        ExternalIdentity identity = new ExternalIdentity("https://dev.local", "dev-sam");
        UserId first = store.upsertUser(identity, "Sam").userId();
        var renamed = store.upsertUser(identity, "Samwise");
        assertEquals(first, renamed.userId());
        assertEquals("Samwise", renamed.displayName());
        assertTrue(store.findUser(first).orElseThrow().suspendedAt() == null);

        store.suspendUser(first, "gate test");
        var suspended = store.findUser(first).orElseThrow();
        assertEquals(CLOCK.instant(), suspended.suspendedAt());
        assertEquals("gate test", suspended.suspendedReason());
        assertEquals("Samwise", store.upsertUser(identity, "IgnoredWhileSuspended").displayName());
        assertThrows(AuthRejectException.class, () -> store.createSession(first, identity,
                "Samwise", java.util.Set.of(com.kotharworks.auth.principal.GlobalRole.PLAYER),
                CLOCK.instant().plusSeconds(60)));
        assertThrows(IllegalArgumentException.class,
                () -> store.suspendUser(new UserId(UUID.randomUUID()), "nobody"));
    }

    @Test
    void tokenDigestLookupFailsClosedOnSuspensionAndRevocationIsOwnerAndSeatScoped() {
        InMemoryAuthStore store = new InMemoryAuthStore(CLOCK);
        ExternalIdentity identity = new ExternalIdentity("https://dev.local", "dev-sam");
        UserId owner = store.upsertUser(identity, "Sam").userId();
        UserId otherOwner = store.upsertUser(
                new ExternalIdentity("https://dev.local", "dev-alice"), "Alice").userId();
        var created = store.createToken(owner, "wilds:seat", 3L, "label", "iw_bot_pref_s3cret",
                "pref", "digest".getBytes());
        assertEquals(1L, created.tokenId());
        ScopedTokenRecord active = store.findActiveTokenBySha256("digest".getBytes())
                .orElseThrow();
        assertFalse(active.revoked());
        assertFalse(active.ownerSuspended());
        assertEquals(3L, active.scopedResourceId());

        // Foreign owner or wrong seatId cannot revoke tokenId=1.
        assertFalse(store.revokeToken(otherOwner, "wilds:seat", 3L, created.tokenId()));
        assertFalse(store.revokeToken(owner, "wilds:seat", 99L, created.tokenId()));
        assertFalse(store.findActiveTokenBySha256("digest".getBytes()).orElseThrow().revoked());

        store.suspendUser(owner, "suspended");
        assertTrue(store.findActiveTokenBySha256("digest".getBytes()).orElseThrow()
                .ownerSuspended());

        assertTrue(store.revokeToken(owner, "wilds:seat", 3L, created.tokenId()));
        assertTrue(store.findActiveTokenBySha256("digest".getBytes()).orElseThrow().revoked());
        assertTrue(store.findActiveTokenBySha256("other".getBytes()).isEmpty());
    }

    @Test
    void sessionCreateFindRevokeAndExpiryRoundTrip() {
        InMemoryAuthStore store = new InMemoryAuthStore(CLOCK);
        ExternalIdentity identity = new ExternalIdentity("https://dev.local", "dev-alice");
        UserId userId = store.upsertUser(identity, "Alice").userId();
        var created = store.createSession(userId, identity, "Alice",
                java.util.Set.of(com.kotharworks.auth.principal.GlobalRole.PLAYER),
                CLOCK.instant().plusSeconds(3600));
        assertTrue(store.findActiveSessionBySha256(created.record().sessionSha256()).isPresent());
        assertTrue(store.findByDigest(created.record().sessionSha256()).isPresent());
        store.revokeSession(created.record().sessionSha256());
        assertTrue(store.findActiveSessionBySha256(created.record().sessionSha256()).isEmpty());

        var alreadyExpired = store.createSession(userId, identity, "Alice",
                java.util.Set.of(com.kotharworks.auth.principal.GlobalRole.PLAYER),
                CLOCK.instant());
        assertTrue(store.findByDigest(alreadyExpired.record().sessionSha256()).isEmpty());
    }

    @Test
    void seatRateLimiterHandlesNegativeNanoTimeSubMillisecondPollingAndExtremeCaps() {
        long[] nanos = new long[] {-10_000_000_000L}; // negative System.nanoTime() origin
        SeatRateLimiter limiter = new SeatRateLimiter(10L, 3L, () -> nanos[0]);

        // Initial burst of 3 succeeds even when nanoTime() < 0, 4th rejects.
        assertTrue(limiter.tryAcquire(1L));
        assertTrue(limiter.tryAcquire(1L));
        assertTrue(limiter.tryAcquire(1L));
        assertFalse(limiter.tryAcquire(1L));

        // 1,000 rapid polls spaced by 100 microseconds (= 100 ms total = 1 token at 10/s)
        // must accumulate exact nanotokens without truncation starvation.
        int admitted = 0;
        for (int i = 0; i < 1_000; i++) {
            nanos[0] += 100_000L; // +0.1 ms
            if (limiter.tryAcquire(1L)) {
                admitted++;
            }
        }
        assertEquals(1, admitted, "sub-millisecond polling must earn exact tokens over 100ms");

        // Extreme Long.MAX_VALUE rate/burst never overflows.
        SeatRateLimiter extreme = new SeatRateLimiter(Long.MAX_VALUE, Long.MAX_VALUE, () -> nanos[0]);
        assertTrue(extreme.tryAcquire(7L));
        nanos[0] += 5_000_000_000L;
        assertTrue(extreme.tryAcquire(7L));
    }

    private static WorldEvent sampleEvent() {
        return new WorldEvent.OwnerRegistered(new OwnerId(0, 1), new ActorId(0, 1), 1L, 5L,
                new OwnerId(0, 1), "pub", "priv");
    }
}
