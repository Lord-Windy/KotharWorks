# Ticket 02 — Generational IDs, Dense EntityTable, Integer LazyGauge & DueEventHeap

- **`st` Key:** `29b0e00a` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Backlog *(auto-promotes when `4e263046` completes)*
- **Depends on:** Ticket `01` (`4e263046`)
- **Modules touched:** `libs/wilds`

---

## 1. Objective

Implement the gameplay-neutral, data-oriented simulation data structures in `libs/wilds`:
1. **Generational Typed IDs** (`OwnerId`, `ActorId`, `PlaceId`, `RuleId`) with compact canonical wire formatting.
2. **Dense Array `EntityTable<I, R>`** providing O(1) lookup, stale-handle rejection on slot reuse, and deterministic slot-index iteration.
3. **100% Integer `LazyGauge`** for zero-tick continuous linear interpolation without floating-point drift.
4. **Deterministic `DueEventHeap`** ordered by `(dueAtMs, seq)` with O(1) lazy `cancellationToken` (`heapTicket`) invalidation.

---

## 2. Explicit Non-Goals

- Do **not** add gameplay-specific IDs (`HoldId`, `BuildingId`, `PartyId`, `CharacterId`, `HouseholdId`).
- Do **not** implement `WorldState`, commands, or task mechanics yet (Tickets `03` and `04`).

---

## 3. Deliverables & Structure

### 3.1 Neutral Value IDs & Wire Codec (`com.kotharworks.wilds.id`)
- **`EntityId.java`** (`sealed interface EntityId permits OwnerId, ActorId, PlaceId` — note: `EntityId` itself does **not** extend `Comparable<EntityId>`, avoiding generic `Comparable` inheritance conflicts on concrete ID types):
  - `int index()` (non-negative `>= 0`)
  - `int generation()` (`>= 1`)
  - `String prefix()` (`"own"`, `"act"`, `"plc"`)
  - `default String toWire()` -> `prefix() + ":" + index() + ":" + generation()` (e.g., `"act:0:1"`, `"plc:2:1"`).
- **`OwnerId.java`**, **`ActorId.java`**, **`PlaceId.java`**:
  - Each implements `EntityId, Comparable<Self>` (`OwnerId implements EntityId, Comparable<OwnerId>`, etc.) ordered by `(index, generation)` so sorted collections work deterministically; compact constructors validate `index >= 0` and `generation >= 1`.
  - Static `parse(String wire)` on each ID type (e.g. `ActorId.parse("act:0:1")`), throwing `IllegalArgumentException` on wrong prefix, negative index, `< 1` generation, leading-zero ambiguity, or malformed segments.
- **`RuleId.java`** (`record RuleId(String slug) implements Comparable<RuleId>`):
  - Compact constructor validating non-empty `[a-z][a-z0-9_]{0,63}` format.

### 3.2 Dense Generational `EntityTable` (`com.kotharworks.wilds.sim`)
- **`EntityTable.java`** (`final class EntityTable<I extends EntityId, R>`):
  - Backed by dense arrays (`Object[] rows`, `int[] generations`, `int highWaterMark` tracking ever-initialized slots `0 .. highWaterMark - 1`, deterministic **min-order** `int[]` free-list/min-heap over unoccupied slots `< highWaterMark`, `int liveCount`) plus an ID factory `(int index, int generation) -> I` and ID extractor `Function<R, I>` *(isolate the single `(R) rows[index]` cast in a small `@SuppressWarnings("unchecked")` private helper so `-Xlint:all,-serial -Werror` stays clean)*.
  - Operations:
    - `I nextId()` / `R insertNew(Function<I, R> rowBuilder)`: **always** allocates the **lowest unoccupied slot index `< highWaterMark`** (never LIFO stack order!), or appends at `highWaterMark++` initializing `generations[index] = 1` on first use. Because free-slot selection is strictly min-index, free-list order is a pure function of which slots `< highWaterMark` are empty — guaranteeing identical ID allocation before and after snapshot restore!
    - `Optional<R> get(I id)` / `R getOrNull(I id)`: returns row **only if** `0 <= id.index() < highWaterMark`, slot is occupied, and `generations[id.index()] == id.generation()`; returns empty/`null` for stale generations!
    - `void replace(R updatedRow)`: replaces existing live row whose `id` matches current slot & generation (throws if stale/missing).
    - `boolean remove(I id)`: if live and generation matches, clears slot, increments `generations[index]` (so any cached old `I` immediately becomes stale!), inserts `index` into the min-order free-list, and decrements `liveCount`.
    - `List<R> snapshotRows()` & `void forEach(Consumer<R> consumer)`: iterates strictly in ascending `0 .. highWaterMark - 1` slot index order (skipping empty slots) — **100% deterministic**.
    - Snapshot dump & restoration loader preserving exact `highWaterMark` and `(index, generation, nullableRow)` for `0 .. highWaterMark - 1` (rebuilding the min-order free-list from null slots `< highWaterMark`, independent of over-allocated `rows.length` backing capacity).

### 3.3 Integer `LazyGauge` (`com.kotharworks.wilds.sim`)
- **`LazyGauge.java`**:
  ```java
  public record LazyGauge(
      long anchorMilli,
      long anchorTimeMs,
      long rateMilliPerSec,
      long minMilli,
      long maxMilli
  )
  ```
  - Validates `minMilli <= maxMilli` and `anchorMilli` within `[minMilli, maxMilli]`.
  - `long valueMilliAt(long nowMs)`: returns `anchorMilli` when `nowMs <= anchorTimeMs` or `rateMilliPerSec == 0L`; otherwise computes `(elapsedMs * rateMilliPerSec) / 1_000L + anchorMilli` using **saturation-safe** integer math (catching `Math.multiplyExact` / `Math.addExact` `ArithmeticException` or using `BigInteger` so multi-month idle jumps saturate cleanly to `maxMilli`/`minMilli` instead of overflowing or throwing!) clamped to `[minMilli, maxMilli]`.
  - `LazyGauge reanchor(long nowMs, long lumpDeltaMilli, long newRateMilliPerSec)`: materializes current value at `nowMs` (never moving `anchorTimeMs` backward if `nowMs < anchorTimeMs`), adds `lumpDeltaMilli` clamped to `[minMilli, maxMilli]`, and returns a new `LazyGauge` anchored at `Math.max(nowMs, anchorTimeMs)`.

### 3.4 Deterministic `DueEventHeap` (`com.kotharworks.wilds.sim`)
- **`DuePayload.java`** (`sealed interface DuePayload`):
  - `record CompleteActorTask(ActorId actorId) implements DuePayload {}`
  - `record SystemPulse(RuleId pulseId) implements DuePayload {}` *(extensible hook for future world-level periodic events without hardcoding Tithe Ships or Raids).*
- **`ScheduledEvent.java`**:
  - `record ScheduledEvent(long dueAtMs, long seq, long cancellationToken, DuePayload payload) implements Comparable<ScheduledEvent>`
  - `compareTo` compares `dueAtMs` first, then `seq` strictly ascending (`Long.compare`).
- **`DueEventHeap.java`**:
  - Binary min-heap over `ScheduledEvent[]` (`push`, `peek`, `poll`, `isEmpty`, `size`, `snapshotOrderedList` / `restoreFrom`).
  - Zero `remove(Object)` heap scans — active task cancellation works in O(1) via `cancellationToken` comparison when popped!

---

## 4. Tests & Acceptance Criteria

- **`EntityIdWireTest`:**
  - Round-trip `OwnerId`, `ActorId`, `PlaceId`, and `RuleId` parsing; verify rejection of wrong prefixes (`own:0:1` parsed as `ActorId`), negative indices, generation `0`, trailing garbage, and uppercase/invalid `RuleId` slugs.
- **`EntityTableTest`:**
  - Allocate 5 rows -> remove index `1` and `3` -> verify `get(oldId1)` returns empty -> allocate new row -> verify it reuses index `1` with `generation == 2` -> verify `get(Id(1, 1))` **still** returns empty while `get(Id(1, 2))` returns the new row.
  - Verify `forEach` / `snapshotRows` strictly preserves ascending slot index order regardless of insertion/removal history.
  - Verify snapshot dump & restore preserves unoccupied slots' bumped generation numbers.
- **`LazyGaugeTest`:**
  - Exact integer math at sub-second boundaries (`999ms` elapsed vs `1000ms`), min/max clamping, `reanchor` lump additions/subtractions, and overflow safety across a simulated **365-day (`31_536_000_000 ms`)** idle jump!
- **`DueEventHeapTest`:**
  - **Tie-breaker determinism:** Push 50 events with identical `dueAtMs = 5_000L` in shuffled insertion order (or sequential `seq` order) and prove `poll()` returns strictly ascending `(dueAtMs, seq)` order every time.
  - **10,000-event property test** using `SeededRng` comparing heap pop order against a sorted reference list.
  - Verify `WildsArchitectureGateTest` continues to pass with zero warnings.
