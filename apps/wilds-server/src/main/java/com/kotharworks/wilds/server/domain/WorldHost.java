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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kotharworks.wilds.WildsEngine;
import com.kotharworks.wilds.command.Phase1Result;
import com.kotharworks.wilds.command.WorldCommand;
import com.kotharworks.wilds.event.WorldEvent;
import com.kotharworks.wilds.observe.WorldView;
import com.kotharworks.wilds.replay.WalRecord;
import com.kotharworks.wilds.rules.RuleRegistry;
import com.kotharworks.wilds.seed.WorldSeed;
import com.kotharworks.wilds.server.ports.ClockPort;
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
 * five-step admission on the writer thread: (1) seat-scoped {@code cmdId}
 * idempotency against the bounded receipt cache and the store; (2) a
 * pre-mutation {@code CLOCK_ADVANCE} anchor when due events exist at
 * {@code currentLogicalMs}; (3) 100%-pure Phase-1 validation (rejection &rarr;
 * {@code 409}, zero WAL writes, zero mutation); (4) the command is committed
 * to {@code WorldStorePort} <em>before</em> any state mutation (store failure
 * &rarr; {@code 503 STORAGE_UNAVAILABLE}, RAM untouched); (5) advance to
 * {@code admittedMs}, apply, persist events, publish the fresh view, complete
 * the caller's {@link ReplySlot}, then fan events out to the bus.
 */
public final class WorldHost {

    /** Server wire-protocol version recorded in the world's version triple. */
    public static final int WILDS_PROTOCOL_VERSION = 1;

    /** Quiet-advance threshold: a pump tick writes a CLOCK_ADVANCE at least this often. */
    public static final long DEFAULT_QUIET_ADVANCE_MS = 60_000L;

    /** Default uptime checkpoint cadence (5 minutes), matching {@code --checkpoint-interval-ms}. */
    public static final long DEFAULT_CHECKPOINT_INTERVAL_MS = 300_000L;

    private static final int MAX_RECEIPT_CACHE = 65_536;
    private static final System.Logger LOG = System.getLogger(WorldHost.class.getName());
    private static final long OFFER_TIMEOUT_MS = 5_000L;
    private static final long REPLY_TIMEOUT_MS = 10_000L;

    private record CachedReceipt(long seatId, CommandReceipt receipt) {
    }

    private final WorldStorePort worldStore;
    private final EventBusPort eventBus;
    private final ClockPort clockPort;
    private final long timeScale;
    private final long quietAdvanceMs;
    private final long checkpointIntervalMs;
    private final WorldRecord world;
    private final int worldId;
    private final WildsEngine engine;
    private final ArrayBlockingQueue<HostMessage> mailbox = new ArrayBlockingQueue<>(1024);
    private final Map<UUID, CachedReceipt> receiptCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, CachedReceipt> eldest) {
            return size() > MAX_RECEIPT_CACHE;
        }
    };
    private final List<StoredWorldEvent> pendingEvents = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Thread writerThread;

    private volatile WorldView view;
    private volatile List<StoredWorldEvent> pendingEventsSnapshot = List.of();
    private long lastWalSeq;
    private long lastPersistedClockMs;
    private long lastTickWallNanos;
    private long lastCheckpointWallNanos;

    private sealed interface HostMessage
            permits SubmitCommand, Tick, RequestCheckpoint, Shutdown, Abort {
    }

    private record SubmitCommand(long seatId, UUID cmdId, WorldCommand command,
            ReplySlot replySlot) implements HostMessage {
    }

    private record Tick() implements HostMessage {
        static final Tick INSTANCE = new Tick();
    }

    private record RequestCheckpoint(CountDownLatch done) implements HostMessage {
    }

    private record Shutdown() implements HostMessage {
        static final Shutdown INSTANCE = new Shutdown();
    }

    private record Abort() implements HostMessage {
        static final Abort INSTANCE = new Abort();
    }

    /** The caller-side synchronous reply latch (no futures anywhere). */
    static final class ReplySlot {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile CommandOutcome outcome;

        synchronized void complete(CommandOutcome outcome) {
            if (this.outcome != null) {
                return;
            }
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
     * Boots (or recovers) the world and starts the writer virtual thread over
     * an injected monotonic {@link ClockPort}.
     */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, ClockPort clockPort,
            long worldSeed, long timeScale, long quietAdvanceMs, long checkpointIntervalMs) {
        this.worldStore = Objects.requireNonNull(worldStore, "worldStore");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.clockPort = Objects.requireNonNull(clockPort, "clockPort");
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
            this.lastWalSeq = replayWal(checkpoint.upToWalSeq());
        } else {
            this.engine = new WildsEngine(new WorldSeed(worldSeed));
            this.lastWalSeq = replayWal(0L);
        }
        this.lastPersistedClockMs = engine.nowMs();
        this.lastTickWallNanos = clockPort.nanoTime();
        this.lastCheckpointWallNanos = lastTickWallNanos;
        this.view = engine.view();
        this.writerThread = Thread.ofVirtual().name("wilds-world-host").start(this::runWriter);
    }

    /**
     * Boots (or recovers) the world over {@link ClockPort#SYSTEM}.
     */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, long worldSeed,
            long timeScale, long quietAdvanceMs, long checkpointIntervalMs) {
        this(worldStore, eventBus, ClockPort.SYSTEM, worldSeed, timeScale, quietAdvanceMs,
                checkpointIntervalMs);
    }

    /**
     * Convenience constructor with the default 60 s quiet-advance anchor and
     * the default 5 minute checkpoint cadence.
     */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, long worldSeed,
            long timeScale) {
        this(worldStore, eventBus, ClockPort.SYSTEM, worldSeed, timeScale,
                DEFAULT_QUIET_ADVANCE_MS, DEFAULT_CHECKPOINT_INTERVAL_MS);
    }

    /** Convenience constructor with the default 5 minute checkpoint cadence. */
    public WorldHost(WorldStorePort worldStore, EventBusPort eventBus, long worldSeed,
            long timeScale, long quietAdvanceMs) {
        this(worldStore, eventBus, ClockPort.SYSTEM, worldSeed, timeScale, quietAdvanceMs,
                DEFAULT_CHECKPOINT_INTERVAL_MS);
    }

    // ------------------------------------------------------------------ caller side

    /**
     * Submits one command to the writer and blocks the calling virtual thread
     * until the reply slot completes. Duplicate {@code cmdId}s from the owning
     * seat never re-execute: every caller gets the identical receipt.
     */
    public CommandOutcome submit(long seatId, UUID cmdId, WorldCommand command) {
        Objects.requireNonNull(cmdId, "cmdId");
        Objects.requireNonNull(command, "command");
        if (closed.get()) {
            return new CommandOutcome.StorageUnavailable("world host is closed");
        }
        ReplySlot slot = new ReplySlot();
        SubmitCommand envelope = new SubmitCommand(seatId, cmdId, command, slot);
        try {
            if (!mailbox.offer(envelope, OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return new CommandOutcome.StorageUnavailable("world host mailbox is full");
            }
            if (closed.get() && mailbox.remove(envelope)) {
                return new CommandOutcome.StorageUnavailable("world host is closed");
            }
            return slot.await(REPLY_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandOutcome.StorageUnavailable("interrupted while awaiting the host");
        }
    }

    /** Enqueues a pump tick (non-blocking; dropped when the mailbox is full). */
    public void tick() {
        if (closed.get()) {
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        enqueueTerminalMessage(Shutdown.INSTANCE);
        awaitWriterExit();
        eventBus.close();
    }

    /**
     * Abrupt in-process stand-in for a {@code kill -9} in the crash-recovery
     * rig: stops accepting submissions and stops the writer without flushing
     * deferred events, without the final shutdown clock anchor, and without a
     * checkpoint — exactly the durable state a hard process death leaves
     * behind.
     */
    public void closeWithoutCheckpoint() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        enqueueTerminalMessage(Abort.INSTANCE);
        awaitWriterExit();
        eventBus.close();
    }

    private void enqueueTerminalMessage(HostMessage terminal) {
        while (!mailbox.offer(terminal)) {
            HostMessage evicted = mailbox.poll();
            if (evicted != null) {
                abortQueuedMessage(evicted, "world host is closing");
            }
        }
    }

    private void awaitWriterExit() {
        try {
            writerThread.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ writer side

    private void runWriter() {
        try {
            while (true) {
                HostMessage message = mailbox.take();
                switch (message) {
                    case SubmitCommand submit -> {
                        try {
                            handleSubmit(submit);
                        } catch (RuntimeException unexpected) {
                            LOG.log(System.Logger.Level.ERROR,
                                    "world " + worldId + " unexpected failure handling command "
                                            + submit.cmdId(), unexpected);
                            submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                                    "world host error: " + unexpected.getMessage()));
                        } catch (Throwable fatal) {
                            submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                                    "world host fatal error"));
                            throw fatal;
                        }
                    }
                    case Tick _ -> {
                        try {
                            handleTick();
                        } catch (RuntimeException unexpected) {
                            LOG.log(System.Logger.Level.ERROR,
                                    "world " + worldId + " unexpected failure on tick",
                                    unexpected);
                        }
                    }
                    case RequestCheckpoint req -> {
                        try {
                            writeCheckpointOnWriter();
                        } finally {
                            req.done().countDown();
                        }
                    }
                    case Shutdown _ -> {
                        handleClose();
                        return;
                    }
                    case Abort _ -> {
                        return; // kill -9 fidelity: no flush, no anchor, no checkpoint
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closed.set(true);
            HostMessage leftover;
            while ((leftover = mailbox.poll()) != null) {
                abortQueuedMessage(leftover, "world host is closed");
            }
        }
    }

    private static void abortQueuedMessage(HostMessage message, String reason) {
        switch (message) {
            case SubmitCommand submit -> submit.replySlot().complete(
                    new CommandOutcome.StorageUnavailable(reason));
            case RequestCheckpoint req -> req.done().countDown();
            case Tick _, Shutdown _, Abort _ -> {
                // No caller waiting.
            }
        }
    }

    private void handleSubmit(SubmitCommand submit) {
        UUID cmdId = submit.cmdId();
        // (1) Seat-scoped idempotency: bounded cache first, then store lookup.
        CachedReceipt cached = receiptCache.get(cmdId);
        if (cached != null) {
            if (cached.seatId() != submit.seatId()) {
                submit.replySlot().complete(new CommandOutcome.Rejected(
                        "FORBIDDEN", 0L, "cmdId belongs to another seat"));
                return;
            }
            submit.replySlot().complete(new CommandOutcome.Ok(cached.receipt()));
            return;
        }
        Optional<WalRecord.CommandEntry> priorCommitted;
        try {
            priorCommitted = worldStore.findCommandByCmdId(worldId, cmdId);
        } catch (RuntimeException storeFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "world {0} store refused cmdId check for {1}: {2}",
                    worldId, cmdId, storeFailure.getMessage());
            submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                    "store refused idempotency check: " + storeFailure.getMessage()));
            return;
        }
        if (priorCommitted.isPresent()) {
            WalRecord.CommandEntry prior = priorCommitted.get();
            if (prior.seatId() != submit.seatId()) {
                submit.replySlot().complete(new CommandOutcome.Rejected(
                        "FORBIDDEN", 0L, "cmdId belongs to another seat"));
                return;
            }
            // command_wal is strictly pre-mutation and append-only (zero UPDATEs),
            // so pre-checkpoint / cache-evicted reconstruction returns the exact
            // (cmdId, admittedMs, walSeq) plus the target actor's current live
            // revision for subsequent optimistic expectedRevision calls.
            CommandReceipt rebuilt = new CommandReceipt(cmdId, prior.admittedMs(),
                    prior.walSeq(), revisionFor(prior.command(), engine.view()));
            receiptCache.put(cmdId, new CachedReceipt(prior.seatId(), rebuilt));
            submit.replySlot().complete(new CommandOutcome.Ok(rebuilt));
            return;
        }

        // (2) Determine admittedMs from current logical time; commit CLOCK_ADVANCE first
        //     when due events exist <= admittedMs.
        long wallNow = clockPort.nanoTime();
        long admittedMs = currentLogicalMs(wallNow);
        OptionalLong nextDue = engine.peekNextDueMs();
        if (nextDue.isPresent() && nextDue.getAsLong() <= admittedMs) {
            try {
                lastWalSeq = worldStore.appendClockAdvance(worldId, admittedMs, null);
            } catch (RuntimeException storeFailure) {
                LOG.log(System.Logger.Level.WARNING,
                        "world {0} store refused clock anchor at {1}ms: {2}",
                        worldId, admittedMs, storeFailure.getMessage());
                submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                        "store refused clock anchor: " + storeFailure.getMessage()));
                return;
            }
            engine.advanceTo(admittedMs);
            lastPersistedClockMs = engine.nowMs();
            lastTickWallNanos = wallNow;
            List<StoredWorldEvent> anchored = drainEmitted();
            boolean flushed = tryFlushPendingEvents("clock-anchor");
            eventBus.publish(worldId, anchored);
            if (!flushed) {
                submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                        "store unavailable after clock anchor; command not committed"));
                return;
            }
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
            LOG.log(System.Logger.Level.WARNING,
                    "world {0} store refused command commit for {1}: {2}",
                    worldId, cmdId, storeFailure.getMessage());
            submit.replySlot().complete(new CommandOutcome.StorageUnavailable(
                    "store refused command commit: " + storeFailure.getMessage()));
            return;
        }
        // (5) Advance clock to admittedMs (matching WAL replay), apply, persist, publish, fan out.
        lastWalSeq = walSeq;
        if (admittedMs > engine.nowMs()) {
            engine.advanceTo(admittedMs);
        }
        lastPersistedClockMs = engine.nowMs();
        lastTickWallNanos = wallNow;
        Phase1Result applied = engine.apply(submit.command());
        if (applied instanceof Phase1Result.Rejected bugGuard) {
            throw new IllegalStateException("WAL-committed command rejected at apply: " + cmdId
                    + " (" + bugGuard.errorCode() + ")");
        }
        List<StoredWorldEvent> emitted = drainEmitted();
        tryFlushPendingEvents("post-apply " + cmdId);
        CommandReceipt receipt = new CommandReceipt(cmdId, admittedMs, walSeq,
                revisionFor(submit.command(), engine.view()));
        receiptCache.put(cmdId, new CachedReceipt(submit.seatId(), receipt));
        submit.replySlot().complete(new CommandOutcome.Ok(receipt));
        eventBus.publish(worldId, emitted);
    }

    private void handleTick() {
        long wallNow = clockPort.nanoTime();
        long target = currentLogicalMs(wallNow);
        OptionalLong nextDue = engine.peekNextDueMs();
        boolean duePending = nextDue.isPresent() && nextDue.getAsLong() <= target;
        boolean quietDue = target - lastPersistedClockMs >= quietAdvanceMs;
        // toNanos saturates at Long.MAX_VALUE, so Long.MAX_VALUE disables periodic writes
        boolean checkpointDue = wallNow - lastCheckpointWallNanos
                >= TimeUnit.MILLISECONDS.toNanos(checkpointIntervalMs);
        if (!duePending && !quietDue) {
            if (!pendingEvents.isEmpty()) {
                tryFlushPendingEvents("idle-tick");
            }
            if (checkpointDue) {
                writeCheckpointOnWriter();
            }
            return; // zero idle WAL spam; wall time keeps accumulating for the next tick
        }
        try {
            lastWalSeq = worldStore.appendClockAdvance(worldId, target, null);
        } catch (RuntimeException storeFailure) {
            // Stay paused at the last committed millisecond; retry on the next tick.
            LOG.log(System.Logger.Level.WARNING,
                    "world {0} paused tick at {1}ms (store unavailable): {2}",
                    worldId, engine.nowMs(), storeFailure.getMessage());
            return;
        }
        engine.advanceTo(target);
        lastPersistedClockMs = engine.nowMs();
        lastTickWallNanos = wallNow;
        List<StoredWorldEvent> emitted = drainEmitted();
        tryFlushPendingEvents("tick");
        eventBus.publish(worldId, emitted);
        if (checkpointDue) {
            writeCheckpointOnWriter();
        }
    }

    private long currentLogicalMs(long wallNowNanos) {
        long wallDeltaMs = Math.max(0L, (wallNowNanos - lastTickWallNanos) / 1_000_000L);
        long now = engine.nowMs();
        long headroom = Math.max(0L, Long.MAX_VALUE - now);
        long maxUnscaled = Long.MAX_VALUE / timeScale;
        long scaledDelta = wallDeltaMs >= maxUnscaled ? headroom : wallDeltaMs * timeScale;
        return now + Math.min(scaledDelta, headroom);
    }

    /**
     * Writes a checkpoint of the live world best-effort: routes through the
     * single writer thread when called externally, flushes any deferred events
     * first (so a checkpoint never advances {@code upToWalSeq} past unpersisted
     * events), and swallows store failures so the WAL tail recovers the
     * difference on the next boot.
     */
    public void writeCheckpointNow() {
        if (Thread.currentThread() == writerThread) {
            writeCheckpointOnWriter();
            return;
        }
        if (closed.get()) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        try {
            if (mailbox.offer(new RequestCheckpoint(done), OFFER_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS)) {
                done.await(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeCheckpointOnWriter() {
        try {
            flushPendingEvents();
            worldStore.saveCheckpoint(worldId, lastWalSeq, engine.nowMs(), engine.worldHash(),
                    engine.snapshotJson());
            lastCheckpointWallNanos = clockPort.nanoTime();
        } catch (RuntimeException storeFailure) {
            // Best effort; the WAL tail recovers the difference on the next boot.
            LOG.log(System.Logger.Level.WARNING,
                    "world {0} checkpoint skipped (store unavailable): {1}",
                    worldId, storeFailure.getMessage());
        }
    }

    private void handleClose() {
        try {
            flushPendingEvents();
            // Graceful SIGTERM anchor: commit CLOCK_ADVANCE(currentLogicalMs) and
            // drain advanceTo(currentLogicalMs) before checkpointing so sub-quiet
            // elapsed uptime is preserved and the exported WAL matches the
            // shutdown checkpoint hash bit-for-bit.
            long wallNow = clockPort.nanoTime();
            long targetMs = currentLogicalMs(wallNow);
            OptionalLong nextDue = engine.peekNextDueMs();
            boolean duePending = nextDue.isPresent() && nextDue.getAsLong() <= targetMs;
            if (lastWalSeq == 0L || targetMs > lastPersistedClockMs || duePending) {
                lastWalSeq = worldStore.appendClockAdvance(worldId, targetMs, null);
                engine.advanceTo(targetMs);
                lastPersistedClockMs = engine.nowMs();
                lastTickWallNanos = wallNow;
                drainEmitted();
                flushPendingEvents();
            }
        } catch (RuntimeException storeFailure) {
            // Best effort on shutdown; the WAL tail recovers the difference.
            LOG.log(System.Logger.Level.WARNING,
                    "world {0} shutdown anchor skipped: {1}",
                    worldId, storeFailure.getMessage());
        }
        writeCheckpointOnWriter();
    }

    /**
     * Replays every WAL row after {@code afterWalSeq} in ascending order,
     * rebuilding the {@code cmdId} receipt cache and re-backfilling events.
     */
    private long replayWal(long afterWalSeq) {
        long lastSeq = afterWalSeq;
        for (WalRecord record : worldStore.loadWalAfter(worldId, afterWalSeq)) {
            lastSeq = Math.max(lastSeq, switch (record) {
                case WalRecord.ClockAdvance advance -> advance.walSeq();
                case WalRecord.CommandEntry entry -> entry.walSeq();
            });
            switch (record) {
                case WalRecord.ClockAdvance advance -> {
                    engine.advanceTo(advance.admittedMs());
                    backfillEvents();
                }
                case WalRecord.CommandEntry entry -> {
                    UUID cmdId = UUID.fromString(entry.cmdId());
                    if (receiptCache.containsKey(cmdId)) {
                        continue;
                    }
                    engine.advanceTo(entry.admittedMs());
                    Phase1Result applied = engine.apply(entry.command());
                    if (applied instanceof Phase1Result.Rejected rejected) {
                        throw new IllegalStateException("WAL replay rejected committed command "
                                + entry.cmdId() + ": " + rejected.errorCode());
                    }
                    backfillEvents();
                    CommandReceipt receipt = new CommandReceipt(cmdId, entry.admittedMs(),
                            entry.walSeq(), revisionFor(entry.command(), engine.view()));
                    receiptCache.put(cmdId, new CachedReceipt(entry.seatId(), receipt));
                }
            }
        }
        return lastSeq;
    }

    private List<StoredWorldEvent> drainEmitted() {
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
        pendingEvents.addAll(stored);
        pendingEventsSnapshot = List.copyOf(pendingEvents);
        return List.copyOf(stored);
    }

    private void flushPendingEvents() {
        if (pendingEvents.isEmpty()) {
            return;
        }
        worldStore.appendEvents(worldId, List.copyOf(pendingEvents));
        pendingEvents.clear();
        pendingEventsSnapshot = List.of();
    }

    private boolean tryFlushPendingEvents(String context) {
        try {
            flushPendingEvents();
            return true;
        } catch (RuntimeException storeFailure) {
            LOG.log(System.Logger.Level.WARNING,
                    "world {0} deferred event persistence ({1}): {2}",
                    worldId, context, storeFailure.getMessage());
            return false;
        }
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

    /**
     * Reads private events for {@code seatId} with {@code cursor > sinceCursor}
     * up to {@code limit}, merging persisted rows with any WAL-committed events
     * currently staged in {@code pendingEvents} during a transient store blip
     * so SSE reconnects and catch-up queries never drop cursors.
     */
    public List<StoredWorldEvent> readSeatEventsSince(long seatId, long sinceCursor, int limit) {
        List<StoredWorldEvent> persisted = worldStore.readEventsSince(
                worldId, seatId, sinceCursor, limit);
        List<StoredWorldEvent> deferred = pendingEventsSnapshot;
        if (deferred.isEmpty() || persisted.size() >= limit) {
            return persisted;
        }
        long floor = persisted.isEmpty() ? sinceCursor : persisted.getLast().cursor();
        List<StoredWorldEvent> merged = new ArrayList<>(persisted);
        for (StoredWorldEvent event : deferred) {
            if (merged.size() >= limit) {
                break;
            }
            if (event.cursor() > floor && Objects.equals(event.seatId(), seatId)) {
                merged.add(event);
                floor = event.cursor();
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Reads the most recent {@code limit} private events for {@code seatId} in
     * ascending {@code cursor} order, merging persisted rows with any newer
     * WAL-committed events in {@code pendingEvents}.
     */
    public List<StoredWorldEvent> readRecentSeatEvents(long seatId, int limit) {
        List<StoredWorldEvent> persisted = worldStore.readRecentEvents(worldId, seatId, limit);
        List<StoredWorldEvent> deferred = pendingEventsSnapshot;
        if (deferred.isEmpty()) {
            return persisted;
        }
        long floor = persisted.isEmpty() ? 0L : persisted.getLast().cursor();
        List<StoredWorldEvent> merged = new ArrayList<>(persisted);
        for (StoredWorldEvent event : deferred) {
            if (event.cursor() > floor && Objects.equals(event.seatId(), seatId)) {
                merged.add(event);
                floor = event.cursor();
            }
        }
        int start = Math.max(0, merged.size() - limit);
        return List.copyOf(merged.subList(start, merged.size()));
    }

    /**
     * Reads the most recent {@code limit} public-projected world events in
     * ascending {@code cursor} order, merging persisted rows with any newer
     * WAL-committed public events in {@code pendingEvents}.
     */
    public List<StoredWorldEvent> readRecentPublicEvents(int limit) {
        List<StoredWorldEvent> persisted = worldStore.readRecentEvents(worldId, null, limit);
        List<StoredWorldEvent> deferred = pendingEventsSnapshot;
        if (deferred.isEmpty()) {
            return persisted;
        }
        long floor = persisted.isEmpty() ? 0L : persisted.getLast().cursor();
        List<StoredWorldEvent> merged = new ArrayList<>(persisted);
        for (StoredWorldEvent event : deferred) {
            if (event.cursor() > floor && event.publicSummary() != null) {
                merged.add(StoredWorldEvent.publicProjection(event));
                floor = event.cursor();
            }
        }
        int start = Math.max(0, merged.size() - limit);
        return List.copyOf(merged.subList(start, merged.size()));
    }

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
