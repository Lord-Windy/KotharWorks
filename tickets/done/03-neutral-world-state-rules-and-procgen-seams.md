# Ticket 03 — Neutral WorldState, RuleRegistry, Procgen Seams, JsonCodec & WorldHash

- **`st` Key:** `0aaf30bb` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Done
- **Depends on:** Ticket `02` (`29b0e00a`)
- **Modules touched:** `libs/wilds`

---

## 1. Objective

Assemble the gameplay-neutral `WorldState` schema, compile-time `RuleRegistry`, explicit procedural-generation strategy seams (`WorldTopologyBootstrap` and `DescriptionComposer`), zero-dependency deterministic `JsonCodec`, `SnapshotCodec`, and canonical `WorldHash` inside `libs/wilds`.

---

## 2. Explicit Non-Goals

- Do **not** implement `libs/procgen` or real terrain/biome/name/prose algorithms; provide clean `@FunctionalInterface` seams in `libs/wilds` backed by minimal deterministic stubs.
- Do **not** add gameplay tables or fields (no inventories, skills, health, combat doctrines, buildings, parties, or holds).
- Do **not** add Jackson or any external JSON library to `libs/wilds/pom.xml`.

---

## 3. Deliverables & Structure

### 3.1 Compile-Time `RuleRegistry` (`com.kotharworks.wilds.rules`)
- **`RuleRegistry.java`**:
  - `public static final int WILDS_RULES_VERSION = 1;`
  - Immutable `SequencedMap` / `TreeMap`-backed registry of:
    - `PlaceKindDef(RuleId id, String name)` — stock kinds: `camp`, `trail`, `landmark`.
    - `TaskKindDef(RuleId id, String name, long minDurationMs, long maxDurationMs, int maxBatches)` — stock kinds: `wait` (`1_000ms..3_600_000ms`, max 10 batches), `travel` (edge-driven duration, max 1 batch).
  - `RuleRegistry.minimalDefault()` factory providing the stock structural catalog.

### 3.2 Neutral Entity Rows & `WorldState` (`com.kotharworks.wilds.state`)
- **`OwnerRow.java`**:
  ```java
  public record OwnerRow(
      OwnerId id,
      long externalSeatKey,         // Deterministic link to server seat_id (pure long, zero UUID/Auth!)
      String name,
      long revision,                // Monotonic optimistic-concurrency revision
      PlaceId homePlaceId,
      List<PlaceId> discoveredPlaces // Canonical sorted, deduplicated immutable list
  )
  ```
- **`PlaceRow.java` & `PlaceEdge.java`**:
  ```java
  public record PlaceEdge(PlaceId to, long travelDurationMs) {}
  public record PlaceRow(
      PlaceId id,
      RuleId kindId,
      String label,
      List<PlaceEdge> edges         // Immutable list ordered deterministically
  )
  ```
- **`ActorRow.java`, `ActiveTask.java`, `TaskSpec.java`, `QueueFailurePolicy.java`**:
  ```java
  public enum QueueFailurePolicy { ADVANCE, HALT_ON_FAIL }

  public sealed interface TaskSpec permits TaskSpec.Wait, TaskSpec.Travel {
      record Wait(long durationMs, int batches) implements TaskSpec {}
      record Travel(PlaceId destination) implements TaskSpec {}
  }

  public record ActiveTask(
      long startedAtMs,
      long dueAtMs,
      long heapTicket,
      TaskSpec spec,
      int batchesLeft
  ) {}

  public record ActorRow(
      ActorId id,
      OwnerId ownerId,
      String label,
      long revision,                // Increments on queue edit AND on heap task start/finish/fail!
      PlaceId placeId,
      LazyGauge energyMilli,        // 0..100_000 milli-units
      ActiveTask activeTask,        // nullable (null == IDLE_PATIENT)
      List<TaskSpec> pendingQueue,  // Immutable list, max length MAX_PENDING_QUEUE (5)
      QueueFailurePolicy failPolicy
  )
  ```
- **`WorldState.java`**:
  - Holds `WorldSeed seed`, `int rulesVersion`, `long nowMs`, `long nextEventSeq` *(starts at `1L`, monotonic counter for `ScheduledEvent.seq` & `heapTicket`)*, `long nextEventCursor` *(starts at `1L`, monotonic counter for `WorldEvent.cursor` — persisted in state so checkpoint + WAL tail replay in Ticket `08` produces identical event cursors without colliding on `1`!)*, `EntityTable<OwnerId, OwnerRow> owners`, `EntityTable<PlaceId, PlaceRow> places`, `EntityTable<ActorId, ActorRow> actors`, `TreeMap<Long, OwnerId> ownersBySeatKey`, and `DueEventHeap dueEvents`.
  - Wire `WildsArchitectureGateTest.assertPureDeterministicStateRoot(WorldState.class)` so every field, generic type parameter, and `sealed` subtype reachable from `WorldState` is verified free of floats, `HashMap`/`HashSet`, and `SeededRng`!

### 3.3 Procgen Consideration Seams (`com.kotharworks.wilds.worldgen`)
Document clearly in Javadoc how a future `libs/procgen` adapter will plug into these interfaces without modifying engine state or command plumbing:
1. **`WorldTopologyBootstrap.java`** (`@FunctionalInterface`):
   - `void populateInitialTopology(WorldState state, RuleRegistry rules)`
   - **`MinimalSeedTopologyBootstrap`:** Deterministic default using `state.seed().stream("bootstrap", 0, 0)` to create a 3-node connected topology (`plc:0:1` *"Origin Clearing"* <-> `plc:1:1` *"Ridge Trail"* <-> `plc:2:1` *"Old Boundary Stone"*, with symmetric seeded travel durations `edge01Ms` and `edge12Ms` drawn in `3_000..8_000 ms` so `plc:0:1` connects to `plc:1:1` and `plc:1:1` connects to `plc:2:1`, **not** `0 -> 2` directly — enabling multi-hop travel & non-adjacent route tests!).
2. **`DescriptionComposer.java`** (`@FunctionalInterface`):
   - `enum ZoomLevel { GLANCE, LOOK, EXAMINE }`
   - `sealed interface InspectTarget permits InspectTarget.PlaceTarget, InspectTarget.ActorTarget`
   - `String compose(WorldState state, InspectTarget target, ZoomLevel zoom)`
   - **`StubDescriptionComposer`:** Renders crisp, deterministic structural text from live `PlaceRow`/`ActorRow` + `WorldSeed` (no prose grammars yet).

### 3.4 Zero-Dep `JsonCodec`, `SnapshotCodec` & `WorldHash` (`com.kotharworks.wilds.replay`)
- **`JsonCodec.java`**:
  - Compact (~180 LOC) pure Java 25 RFC 8259 parser & canonical writer around `sealed interface JsonValue permits JsonNull, JsonBool, JsonInt, JsonStr, JsonArr, JsonObj` (`JsonInt(long value)` — **rejects floating-point tokens by default** so floats never sneak through JSON!; `JsonObj` preserves deterministic key order via `SequencedMap` / `TreeMap` or immutable entry list without `HashMap`).
  - Shared by `SnapshotCodec`, `CommandCodec` (Ticket `04`), `apps/wilds-headless` (Ticket `05`), and `apps/wilds-server` (Ticket `07`).
- **`WorldHash.java`**:
  - Computes a canonical 64-bit structural digest (first 8 bytes big-endian of `SHA-256` over length-prefixed big-endian primitive writes of `seed`, `rulesVersion`, `nowMs`, `nextEventSeq`, `nextEventCursor`, all `EntityTable` initialized slots `0 .. highWaterMark - 1` + generations *(never raw `rows.length` array capacity!)*, `ownersBySeatKey`, and `dueEvents.snapshotOrderedList()` *(sorted by `(dueAtMs, seq)`, never internal binary-heap array layout!)*).
- **`SnapshotCodec.java`**:
  - `String encode(WorldState state)` and `WorldState decode(String json)` preserving exact table `highWaterMark` and slot generations (`0 .. highWaterMark - 1`), `nowMs`, `nextEventSeq`, `nextEventCursor`, `LazyGauge` anchors, and `dueEvents.snapshotOrderedList()`.

---

## 4. Tests & Acceptance Criteria

- **`WildsArchitectureGateTest`:** Extended to inspect `WorldState.class` recursively — green!
- **`BootstrapTopologyTest`:** Same `WorldSeed` produces identical 3-place graph (`plc:0:1 <-> plc:1:1 <-> plc:2:1`); different seeds vary travel durations deterministically within bounds; `StubDescriptionComposer` returns distinct `GLANCE`, `LOOK`, and `EXAMINE` strings.
- **`JsonCodecTest`:** Escaped strings (`\"`, `\\`, `\n`, `\u00e9`), nested arrays/objects, `Long.MIN_VALUE`/`MAX_VALUE`, rejection of malformed JSON, and rejection of floating-point numbers (`1.5`).
- **`WorldHashAndSnapshotTest`:**
  - Mutating any single scalar (`nowMs`, `nextEventSeq`, an `ActorRow.revision`, a `LazyGauge.anchorMilli`, a bumped empty-slot generation, or a `ScheduledEvent.seq`) produces a different `WorldHash`.
  - `SnapshotCodec.decode(SnapshotCodec.encode(state))` produces an instance whose `WorldHash` matches the original **bit-for-bit**.
