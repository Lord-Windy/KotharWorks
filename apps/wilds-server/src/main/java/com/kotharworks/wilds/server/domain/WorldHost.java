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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.server.ports.EventBusPort;
import com.kotharworks.wilds.server.ports.StoredCheckpoint;
import com.kotharworks.wilds.server.ports.StoredWorldEvent;
import com.kotharworks.wilds.server.ports.WorldRecord;
import com.kotharworks.wilds.server.ports.WorldStorePort;

/**
 * The single writer of the simulation world. One dedicated virtual thread
 * drains a bounded mailbox of command submissions and pump ticks; every other
 * thread reads the world only through the volatile, deeply immutable
 * {@link WorldView} (MVCC-style lock-free reads).
 *
 * <p><strong>Strict Commit-Before-Mutate.</strong> A submitted command runs a
 * five-step admission on the writer thread: (1) {@code cmdId} idempotency
 * against the receipt cache and the store; (2) a pre-mutation
 * {@code CLOCK_ADVANCE} anchor when due events exist; (3) 100%-pure Phase-1
 * validation (rejection &rarr; {@code 409}, zero WAL writes, zero mutation);
 * (4) the command is committed to {@code WorldStorePort} <em>before</em> any
 * state mutation (store failure &rarr; {@code 503 STORAGE_UNAVAILABLE}, RAM
 * untouched); (5) apply, persist events, publish the fresh view, complete the
 * caller's {@link ReplySlot}, then fan events out to the bus.
 */
public final class WorldHost {

    /** Server wire-protocol version recorded in the world's version triple. */
    public static final int WILDS_PROTOCOL_VERSION = 1;

    /** Quiet-advance threshold: a pump tick writes a CLOCK_ADVANCE at least this often. */
    public static final long DEFAULT_QUIET_ADVANCE_MS = 60_000L;

    /** Default uptime checkpoint cadence (5 minutes), matching {@code --checkpoint-interval-ms}. */
    public static final long DEFAULT_CHECKPOINT_INTERVAL_MS = 300_000L;

    private static final long OFFER_TIMEOUT_MS = 5_000L;
    private static final long REPLY_TIMEOUT_MS = 10_000L;

    private final WorldStorePort worldStore;
    private final EventBusPort eventBus;
    private final long timeScale;
    private final long quietAdvanceMs;
    private final long checkpointIntervalMs;
    private final WorldRecord world;
    private final int worldId;
    private final WildsEngine engine;
    private final ArrayBlockingQueue<HostMessage> mailbox = new ArrayBlockingQueue<>(1024);
    private final Map<UUID, CommandReceipt> receiptCache = new HashMap<>();
    private final Thread writerThread;

    private volatile WorldView view;
    private volatile boolean closed;
    private long lastWalSeq;
    private long lastPersistedClockMs;
    private long lastTickWallNanos;
    private long lastCheckpointWallNanos;

    private sealed interface HostMessage permits SubmitCommand, Tick, Shutdown {
    }

    private record SubmitCommand(long seatId, UUID cmdId, WorldCommand command,
            ReplySlot replySlot) implements HostMessage {
    }

    private record Tick() implements HostMessage {
        static final Tick INSTANCE = new Tick();
    }

    private record Shutdown() implements HostMessage {
        static final Shutdown INSTANCE = new Shutdown();
    }

    /** The caller-side synchronous reply latch (no futures anywhere). */
    static final class ReplySlot {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile CommandOutcome outcome;

        void complete(CommandOutcome outcome) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            latch.countDown();
        }

        CommandOutcome await(long timeoutMs) throws InterruptedException {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return new CommandOutcome.StorageUnavailable(
                        "world host did not reply within " + timeoutMs + "ms");
            }
            return outcome;
        }
    }

    /**
     * Boots (or recovers) the world and starts the writer virtual thread. A
     * stored checkpoint is verified by hash and replayed forward from the WAL
     * tail; otherwise a fresh seeded engine is created.
     */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, long worldSeed,
            long timeScale, long quietAdvanceMs, long checkpointIntervalMs) {
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        if (timeScale < 1) {
            throw new IllegalArgumentException("timeScale must be >= 1: " + timeScale);
        }
        if (checkpointIntervalMs < 1) {
            throw new IllegalArgumentException("checkpointIntervalMs must be >= 1: "
                    + checkpointIntervalMs);
        }
        this.timeScale = timeScale;
        this.quietAdvanceMs = quietAdvanceMs;
        this.checkpointIntervalMs = checkpointIntervalMs;
        this.world = worldStore.ensureActiveWorld(worldSeed, RuleRegistry.WILDS_RULES_VERSION,
                WildsEngine.API_VERSION, WILDS_PROTOCOL_VERSION);
        this.worldId = world.worldId();
        StoredCheckpoint checkpoint = worldStore.loadLatestCheckpoint(worldId).orElse(null);
        if (checkpoint != null) {
            this.engine = WildsEngine.fromSnapshotJson(checkpoint.stateJson());
            if (engine.worldHash() != checkpoint.worldHash()) {
                throw new IllegalStateException("checkpoint hash mismatch: stored "
                        + checkpoint.worldHash() + " restored " + engine.worldHash());
            }
            replayWal(checkpoint.upToWalSeq());
        } else {
            this.engine = new WildsEngine(new WorldSeed(worldSeed));
        }
        this.lastWalSeq = 0;
        this.lastPersistedClockMs = engine.nowMs();
        this.lastTickWallNanos = System.nanoTime();
        this.lastCheckpointWallNanos = lastTickWallNanos;
        this.view = engine.view();
        this.writerThread = Thread.ofVirtual().name("wilds-world-host").start(this::runWriter);
    }

    /**
     * Convenience constructor with the default 60 s quiet-advance anchor and
     * the default 5 minute checkpoint cadence.
     */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, long worldSeed,
            long timeScale) {
        this(worldStore, eventBus, worldSeed, timeScale, DEFAULT_QUIET_ADVANCE_MS,
                DEFAULT_CHECKPOINT_INTERVAL_MS);
    }

    /** Convenience constructor with the default 5 minute checkpoint cadence. */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, long worldSeed,
            long timeScale, long quietAdvanceMs) {
        this(worldStore, eventBus, worldSeed, timeScale, quietAdvanceMs,
                DEFAULT_CHECKPOINT_INTERVAL_MS);
    }

    // ------------------------------------------------------------------ caller side

    /**
     * Submits one command to the writer and blocks the calling virtual thread
     * until the reply slot completes. Duplicate {@code cmdId}s never
     * re-execute: every caller gets the identical receipt.
     */
    public CommandOutcome submit(long seatId, UUID cmdId, WorldCommand command) {
        Objects.requireNonNull(cmdId, "cmdId");
        Objects.requireNonNull(command, "command");
        if (closed) {
            return new CommandOutcome.StorageUnavailable("world host is closed");
        }
        ReplySlot slot = new ReplySlot();
        try {
            if (!mailbox.offer(new SubmitCommand(seatId, cmdId, command, slot),
                    OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new CommandOutcome.StorageUnavailable("world host mailbox is full");
            }
            return slot.await(REPLY_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandOutcome.StorageUnavailable("interrupted while awaiting the host");
        }
    }

    /** Enqueues a pump tick (non-blocking; dropped when the mailbox is full). */
    public void tick() {
        if (closed) {
            return;
        }
        mailbox.offer(Tick.INSTANCE);
    }

    /**
     * Stops the host: refuses new submissions, drains the writer through a
     * shutdown message (which checkpoints the world), then closes the event
     * bus so every SSE subscriber wakes.
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        mailbox.offer(Shutdown.INSTANCE);
        try {
            writerThread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        eventBus.close();
    }

    // ------------------------------------------------------------------ writer side

    private void runWriter() {
        try {
            while (true) {
                HostMessage message = mailbox.take();
                if (message instanceof SubmitCommand submit) {
                    handleSubmit(submit);
                } else if (message instanceof Tick) {
                    handleTick();
                } else if (message instanceof Shutdown) {
                    handleClose();
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void handleSubmit(SubmitCommand submit) {
        UUID cmdId = submit.cmdId();
        // (1) Idempotency: in-memory cache first, then the pre-restart store check.
        CommandReceipt cached = receiptCache.get(cmdId);
        if (cached != null) {
            submit.replySlot().complete(new CommandOutcome.Ok(cached));
            return;
        }
        if (worldStore.existsByCmdId(worldId, cmdId)) {
            // Documented edge: committed before a restart but the receipt was not
            // rebuilt (tampered/foreign store). Reply "already committed" without
            // re-executing; the receipt's WAL seq is unknown (0).
            submit.replySlot().complete(new CommandOutcome.Ok(new CommandReceipt(cmdId,
                    engine.nowMs(), 0, revisionFor(submit.command(), engine.view()))));
            return;
        }
        // (2) Pre-mutation clock anchor when due events exist at admission time.
        long admittedMs = engine.nowMs();
        OptionalLong nextDue = engine.peekNextDueMs();
        if (nextDue.isPresent() && nextDue.getAsLong() <= admittedMs) {
            try {
                lastWalSeq = worldStore.appendClockAdvance(worldId, admittedMs, null);
            } catch (RuntimeException storeFailure) {
                submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                        "store refused clock anchor: " + storeFailure.getMessage()));
                return;
            }
            engine.advanceTo(admittedMs);
            List<StoredWorldEvent> anchored = persistEmitted();
            eventBus.publish(worldId, anchored);
        }
        // (3) Pure Phase-1 validation: zero WAL, zero mutation on rejection.
        Phase1Result phase1 = engine.validatePhase1(submit.command());
        if (phase1 instanceof Phase1Result.Rejected rejected) {
            submit.replySlot().complete(new CommandOutcome.Rejected(
                    rejected.errorCode().name(), rejected.currentRevision(), rejected.detail()));
            return;
        }
        // (4) Commit BEFORE mutate: a store failure leaves RAM 100% untouched.
        long walSeq;
        try {
            walSeq = worldStore.appendCommand(worldId, admittedMs, submit.seatId(), cmdId,
                    submit.command(), null);
        } catch (RuntimeException storeFailure) {
            submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                    "store refused command commit: " + storeFailure.getMessage()));
            return;
        }
        // (5) Apply, persist, publish the view, complete the reply, then fan out.
        lastWalSeq = walSeq;
        Phase1Result applied = engine.apply(submit.command());
        if (applied instanceof Phase1Result.Rejected bugGuard) {
            throw new IllegalStateException("WAL-committed command rejected at apply: " + cmdId
                    + " (" + bugGuard.errorCode() + ")");
        }
        List<StoredWorldEvent> emitted = persistEmitted();
        CommandReceipt receipt = new CommandReceipt(cmdId, admittedMs, walSeq,
                revisionFor(submit.command(), engine.view()));
        receiptCache.put(cmdId, receipt);
        submit.replySlot().complete(new CommandOutcome.Ok(receipt));
        eventBus.publish(worldId, emitted);
    }

    private void handleTick() {
        long wallNow = System.nanoTime();
        long wallDeltaMs = Math.max(0L, (wallNow - lastTickWallNanos) / 1_000_000L);
        lastTickWallNanos = wallNow;
        long target = engine.nowMs() + wallDeltaMs * timeScale;
        OptionalLong nextDue = engine.peekNextDueMs();
        boolean duePending = nextDue.isPresent() && nextDue.getAsLong() <= target;
        boolean quietDue = target - lastPersistedClockMs >= quietAdvanceMs;
        // toNanos saturates at Long.MAX_VALUE, so Long.MAX_VALUE disables periodic writes
        boolean checkpointDue = wallNow - lastCheckpointWallNanos
                >= TimeUnit.MILLISECONDS.toNanos(checkpointIntervalMs);
        if (!duePending && !quietDue) {
            if (checkpointDue) {
                writeCheckpointNow();
            }
            return; // zero idle WAL spam
        }
        try {
            lastWalSeq = worldStore.appendClockAdvance(worldId, target, null);
        } catch (RuntimeException storeFailure) {
            // Stay paused at the last committed millisecond; retry on the next tick.
            return;
        }
        engine.advanceTo(target);
        lastPersistedClockMs = engine.nowMs();
        List<StoredWorldEvent> emitted = persistEmitted();
        eventBus.publish(worldId, emitted);
        if (checkpointDue) {
            writeCheckpointNow();
        }
    }

    /**
     * Writes a checkpoint of the live world best-effort: a store failure is
     * swallowed (never propagates to the single writer thread), and the WAL
     * tail recovers the difference on the next boot.
     */
    public void writeCheckpointNow() {
        try {
            worldStore.saveCheckpoint(worldId, lastWalSeq, engine.nowMs(), engine.worldHash(),
                    engine.snapshotJson());
            lastCheckpointWallNanos = System.nanoTime();
        } catch (RuntimeException storeFailure) {
            // Best effort; the WAL tail recovers the difference on the next boot.
        }
    }

    private void handleClose() {
        try {
            // Final PAUSE_WHILE_DOWN anchor so the exported WAL ends exactly at
            // the checkpointed logical ms and headless replay lands on the same
            // world hash.
            lastWalSeq = worldStore.appendClockAdvance(worldId, engine.nowMs(), null);
            engine.advanceTo(engine.nowMs());
            persistEmitted();
        } catch (RuntimeException storeFailure) {
            // Best effort on shutdown; the WAL tail recovers the difference.
        }
        writeCheckpointNow();
    }

    private void replayWal(long afterWalSeq) {
        for (WalRecord record : worldStore.loadWalAfter(worldId, afterWalSeq)) {
            switch (record) {
                case WalRecord.ClockAdvance advance -> {
                    if (advance.admittedMs() > engine.nowMs()) {
                        engine.advanceTo(advance.admittedMs());
                    }
                    backfillEvents();
                }
                case WalRecord.CommandEntry entry -> {
                    UUID cmdId = UUID.fromString(entry.cmdId());
                    if (receiptCache.containsKey(cmdId)) {
                        continue;
                    }
                    if (entry.admittedMs() > engine.nowMs()) {
                        engine.advanceTo(entry.admittedMs());
                    }
                    Phase1Result applied = engine.apply(entry.command());
                    if (applied instanceof Phase1Result.Rejected rejected) {
                        throw new IllegalStateException("WAL replay rejected committed command "
                                + entry.cmdId() + ": " + rejected.errorCode());
                    }
                    backfillEvents();
                    receiptCache.put(cmdId, new CommandReceipt(cmdId, entry.admittedMs(),
                            entry.walSeq(), revisionFor(entry.command(), engine.view())));
                }
            }
        }
    }

    /** Persists drained events idempotently and republishes the volatile view. */
    private List<StoredWorldEvent> persistEmitted() {
        List<WorldEvent> events = engine.drainEvents();
        WorldView fresh = engine.view();
        this.view = fresh;
        if (events.isEmpty()) {
            return List.of();
        }
        List<StoredWorldEvent> stored = new ArrayList<>(events.size());
        for (WorldEvent event : events) {
            stored.add(StoredWorldEvent.of(event, seatKeyFor(fresh, event)));
        }
        worldStore.appendEvents(worldId, stored);
        return stored;
    }

    private void backfillEvents() {
        List<WorldEvent> events = engine.drainEvents();
        this.view = engine.view();
        if (events.isEmpty()) {
            return;
        }
        List<StoredWorldEvent> stored = new ArrayList<>(events.size());
        for (WorldEvent event : events) {
            stored.add(StoredWorldEvent.of(event, seatKeyFor(view, event)));
        }
        worldStore.appendEvents(worldId, stored);
    }

    private long seatKeyFor(WorldView snapshot, WorldEvent event) {
        for (Map.Entry<Long, com.kotharworks.wilds.id.OwnerId> entry
                : snapshot.ownersBySeatKey().entrySet()) {
            if (entry.getValue().equals(event.scopeOwner())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("no seat key owns " + event.scopeOwner().toWire());
    }

    private long revisionFor(WorldCommand command, WorldView snapshot) {
        return switch (command) {
            case WorldCommand.EnqueueTasks c -> snapshot.actor(c.targetActor())
                    .map(actor -> actor.revision()).orElse(0L);
            case WorldCommand.ReplacePendingQueue c -> snapshot.actor(c.targetActor())
                    .map(actor -> actor.revision()).orElse(0L);
            case WorldCommand.CancelQueue c -> snapshot.actor(c.targetActor())
                    .map(actor -> actor.revision()).orElse(0L);
            case WorldCommand.SetFailurePolicy c -> snapshot.actor(c.targetActor())
                    .map(actor -> actor.revision()).orElse(0L);
            case WorldCommand.RegisterOwner _ -> 0L;
        };
    }

    // ------------------------------------------------------------------ reads (lock-free)

    /** The freshest published {@link WorldView} — deeply immutable, lock-free to read. */
    public WorldView view() {
        return view;
    }

    /** The permanent world metadata (id + version triple). */
    public WorldRecord worldRecord() {
        return world;
    }

    /** The world id every store row keys on. */
    public int worldId() {
        return worldId;
    }

    /** Current logical time in milliseconds (writer-published view). */
    public long nowMs() {
        return view.nowMs();
    }

    /** Canonical 64-bit digest of the writer-published view. */
    public long worldHash() {
        return view.worldHash();
    }

    /** The engine's protocol version constant (wire contract). */
    public int protocolVersion() {
        return WILDS_PROTOCOL_VERSION;
    }
}
