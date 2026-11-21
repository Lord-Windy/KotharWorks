# Ticket 04 — Commands, Two-Phase Task Queues, Events, Fog Projector & Golden Harness

- **`st` Key:** `d8ed0e93` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Backlog *(auto-promotes when `0aaf30bb` completes)*
- **Depends on:** Ticket `03` (`0aaf30bb`)
- **Modules touched:** `libs/wilds`, `PROJECTS.md` *(mark `libs/wilds` `in-development`)*

---

## 1. Objective

Complete the pure `libs/wilds` simulation engine (`WildsEngine` facade):
1. **Closed `sealed interface WorldCommand`** with **100% side-effect-free Phase-1 validation** (`expectedRevision` optimistic concurrency -> `STALE_STATE`) and deterministic execution.
2. **Two-Phase Task Queue Runner (`TaskMechanic<P>`)** supporting stock `Wait` and `Travel` tasks, multi-batching, optimistic chain queuing (`[Travel(B), Travel(C)]`), fail-soft advancement (`ADVANCE` vs `HALT_ON_FAIL`), continuous `LazyGauge` energy updates, and O(1) `heapTicket` active cancellation.
3. **Outward-Only `WorldEvent` Outbox** with monotonic `cursor`s.
4. **Immutable `WorldView` & Fog-of-War `ObservationProjector`** so clients never touch raw state rows or see undiscovered places / foreign queues.
5. **Canonical `CommandCodec` + `WalRecord` Codec + Golden Replay Harness (`-DblessGolden=true`).**

---

## 2. Explicit Non-Goals

- Zero gameplay mechanics: no resource gathering, crafting, construction, inventory capacity, skill XP, combat, raids, or shipments.
- Zero HTTP, threads, sockets, SQL, or `libs/auth` references in `libs/wilds`.

---

## 3. Deliverables & Structure

### 3.1 Commands, Error Codes & Pure Phase-1 Validation (`com.kotharworks.wilds.command`)
- **`ErrorCode.java`**:
  `FORBIDDEN`, `UNKNOWN_TARGET`, `DUPLICATE_SEAT`, `STALE_STATE`, `QUEUE_FULL`, `EMPTY_TASKS`, `UNKNOWN_RULE`, `INVALID_ARGUMENT`, `NO_ROUTE`.
- **`WorldCommand.java`** (`sealed interface WorldCommand`):
  - `record RegisterOwner(long externalSeatKey, String ownerName, String initialActorLabel) implements WorldCommand {}`
    *(Allocates `OwnerRow` at `plc:0:1`, discovers `plc:0:1` + its immediate neighbor `PlaceId`s so the owner has somewhere to travel, and spawns 1 initial `ActorRow` standing `IDLE_PATIENT` at `plc:0:1` with `energyMilli = 100_000`.)*
  - `record EnqueueTasks(OwnerId actorOwner, ActorId targetActor, Long expectedRevision, List<TaskSpec> tasks) implements WorldCommand {}`
  - `record ReplacePendingQueue(OwnerId actorOwner, ActorId targetActor, Long expectedRevision, List<TaskSpec> tasks) implements WorldCommand {}`
  - `record CancelQueue(OwnerId actorOwner, ActorId targetActor, Long expectedRevision, boolean cancelActive) implements WorldCommand {}`
  - `record SetFailurePolicy(OwnerId actorOwner, ActorId targetActor, Long expectedRevision, QueueFailurePolicy policy) implements WorldCommand {}`
- **`Phase1Validator.java`**:
  - **100% pure (zero mutation of `WorldState`, `nextEventSeq`, or `nextEventCursor`):**
    - On `RegisterOwner`: rejects already-registered `externalSeatKey` (`DUPLICATE_SEAT`) and blank `ownerName` / `initialActorLabel` (`INVALID_ARGUMENT`).
    - On actor commands: verifies `actorOwner` exists and `targetActorRow.ownerId().equals(actorOwner)` (`UNKNOWN_TARGET` if actor/owner missing; `FORBIDDEN` if owned by another owner).
    - If `expectedRevision != null && expectedRevision != targetActorRow.revision()`, rejects with `ErrorCode.STALE_STATE` (returning current `revision`).
    - Verifies task list & queue cap:
      - `EnqueueTasks`: non-empty `tasks` (`EMPTY_TASKS`) and `pendingQueue.size() + tasks.size() <= 5` (`QUEUE_FULL`).
      - `ReplacePendingQueue`: non-empty `tasks` (`EMPTY_TASKS`) and `tasks.size() <= 5` (`QUEUE_FULL` — replaces pending queue rather than adding to `pendingQueue.size()`; clearing pending uses `CancelQueue`).
    - Verifies batch limits (`1..maxBatches`), `Wait` duration bounds (`minDurationMs..maxDurationMs`), and target `PlaceId` table existence on `Travel` (`UNKNOWN_TARGET`, **without** requiring the actor to already stand adjacent in Phase 1 — preserving optimistic chain queuing!).

### 3.2 Two-Phase Task Mechanics & `DueEventHeap` Execution (`com.kotharworks.wilds.task`)
- **`TaskMechanic<P extends TaskSpec>`** Strategy interface (`validatePhase1`, `startPhase2`, `onBatchComplete`, `onCancel`):
  - **`WaitMechanic`:** Always succeeds Phase 2; re-anchors `energyMilli` to `+50 milli/sec` while waiting; each completed batch decrements `batchesLeft`; when `batchesLeft == 0`, advances to next pending task (or `IDLE_PATIENT` at `+25 milli/sec`).
  - **`TravelMechanic`:**
    - **Phase 2 Start:** Checks `PlaceRow(actor.placeId)` for a `PlaceEdge` to `destination`.
      - If found: re-anchors `energyMilli` to `-40 milli/sec` (labor drain; if `energyMilli < 15_000`, applies integer `1_350‰` = `1.35×` duration penalty via `(edgeMs * 1_350L) / 1_000L` — proving integer permille speed modifiers with zero floats!) and schedules `CompleteActorTask` on `DueEventHeap`.
      - If **no edge exists** from current `placeId`: emits `WorldEvent.TaskFailed(NO_ROUTE, ...)` and applies `QueueFailurePolicy`:
        - `ADVANCE`: skips failed `Travel` and iteratively attempts the next pending task (`while (activeTask == null && !pendingQueue.isEmpty())`)!
        - `HALT_ON_FAIL`: clears `pendingQueue` and sets actor `IDLE_PATIENT`.
    - **Completion:** Moves actor to `destination`, adds `destination` **and** `destination`'s adjacent edge targets into `OwnerRow.discoveredPlaces` (incrementing `OwnerRow.revision` and emitting `PlaceDiscovered` for newly revealed places!), bumps `ActorRow.revision`, and starts next pending task.
- **O(1) Active Cancellation (`CancelQueue(cancelActive = true)`):**
  - Clears `activeTask` (restoring idle `energyMilli` rate), increments `ActorRow.revision`, and starts next pending task (if any) with a fresh `nextEventSeq` as `heapTicket`. When the old `ScheduledEvent` later pops from `DueEventHeap`, `ev.cancellationToken() != actor.activeTask.heapTicket` skips it in O(1).

### 3.3 Outward-Only Events, Immutable `WorldView` & Fog Projector (`event` & `observe`)
- **`WorldEvent.java`** (`sealed interface WorldEvent`, each carrying `long cursor` allocated from `WorldState.nextEventCursor`, `long occurredMs`, `OwnerId scopeOwner`, `String publicSummary` nullable, and `String privateSummary`):
  `OwnerRegistered` *(sets non-null `publicSummary`, e.g. `"Seat '<ownerName>' entered the world"`, so public feeds & `/chronicle` have safe world-wire events without leaking private actor state)*, `TaskStarted`, `TaskBatchCompleted`, `TaskCancelled`, `TaskFailed`, `PlaceDiscovered` *(private events carry `publicSummary = null` and non-null `privateSummary`)*.
- **`WorldView.java`**:
  - Deeply immutable snapshot wrapper published after each engine step (`nowMs`, `rulesVersion`, `worldHash`, immutable owner/place/actor snapshots, `findOwnerBySeatKey(long)`). Safe for lock-free concurrent reads across thousands of Virtual Threads!
- **`ObservationProjector.java`**:
  - `OwnerObservation projectOwner(WorldView view, OwnerId viewer, DescriptionComposer composer)`:
    - Returns viewer's `OwnerDto`, owned `ActorDto`s (including `revision`, `energyMilliAtNow`, `activeTask`, `pendingQueue`, `failPolicy`, and current place `look` string), and **only** `PlaceDto`s present in `viewer.discoveredPlaces` — **filtering outgoing `PlaceEdge`s on every `PlaceDto` strictly to `viewer.discoveredPlaces.contains(edge.to())`** so undiscovered neighbor IDs (like `plc:2:1` viewed from `plc:0:1`) never leak through `PlaceDto(plc:1:1).edges()`!
    - **Strict Isolation:** Never leaks other owners' actors/queues or undiscovered places.
  - `String examine(WorldView view, OwnerId viewer, InspectTarget target, ZoomLevel zoom, DescriptionComposer composer)` (rejects undiscovered places or foreign private targets with `FORBIDDEN` / `UNDISCOVERED`).

### 3.4 `WildsEngine` Facade, `CommandCodec`, `WalRecord` & Golden Replay Harness
- Replace hello-world `WildsEngine` (`API_VERSION = 1`) with the real deterministic engine facade (`validatePhase1`, `apply`, `advanceTo`, `advanceToNextEvent`, `peekNextDueMs`, `drainEvents`, `view`, `worldHash`, `snapshotJson`, `fromSnapshotJson`). Update `apps/wilds-server/.../Main.java` and `MainTest.java` minimally to compile cleanly against the updated `WildsEngine`.
- **`CommandCodec.java` & `WalRecord.java`** (`com.kotharworks.wilds.replay`):
  - Deterministic JSON encode/decode for every `WorldCommand` variant plus `WalRecord` (`sealed`: `WalRecord.ClockAdvance(long walSeq, long admittedMs, Long worldHash)` and `WalRecord.CommandEntry(long walSeq, long admittedMs, long seatId, String cmdId, WorldCommand command, Long worldHash)`).
- **`GoldenReplayTest.java`**:
  - Runs a multi-step scripted scenario (2 owners, multi-hop chain travel `[Travel(1), Travel(2)]`, multi-batch wait, active cancel, broken-route `ADVANCE` vs `HALT_ON_FAIL`, energy penalty threshold) and compares final `WorldHash` against `src/test/resources/golden/minimal-spine.hash` (updatable via `-DblessGolden=true`).

---

## 4. Tests & Acceptance Criteria

- **Phase-1 Non-Mutation Proof:** Submit every `ErrorCode` failure case (`FORBIDDEN`, `STALE_STATE`, `QUEUE_FULL`, `INVALID_ARGUMENT`, `DUPLICATE_SEAT`) and assert `engine.worldHash()` and `nextEventSeq` are **100% unchanged** before and after rejection.
- **Optimistic Chain Queue + Fail-Soft Policy Test:**
  - Actor at `plc:0:1` queues `[Travel(plc:1:1), Travel(plc:2:1)]` -> succeeds Phase 1 and walks `0 -> 1 -> 2`, discovering `plc:2:1` upon reaching `plc:1:1`!
  - Actor at `plc:0:1` queues `[Travel(plc:2:1), Wait(2_000, 1)]` (no direct `0 -> 2` edge!):
    - Under `ADVANCE`: emits `TaskFailed(NO_ROUTE)` at `t = 0` and immediately starts `Wait(2_000, 1)`!
    - Under `HALT_ON_FAIL`: emits `TaskFailed(NO_ROUTE)`, clears `Wait`, stands `IDLE_PATIENT`.
- **Active `heapTicket` Cancel Test:** Start `Wait(10_000, 3)`, complete batch 1 at `10_000ms`, cancel active at `14_000ms`, advance past `20_000ms` -> old heap entry is ignored cleanly, `revision` incremented properly.
- **Fog Isolation Test:** Owner 1 walks to `plc:2:1`; Owner 2 stays at `plc:0:1` -> Owner 2's `OwnerObservation` omits `plc:2:1` and omits Owner 1's actors/queues.
- **5,000-Step Seeded Fuzz Test:** Random valid/invalid commands + time jumps across 2 engines running the identical stream -> zero uncaught exceptions, invariants hold (`0 <= pendingQueue.size() <= 5`, `0 <= energy <= 100_000`), and mid-run `SnapshotCodec` clone finishes with the **exact same `WorldHash`** as the continuous engine!
