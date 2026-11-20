# 02 — Commands, Lazy Evaluation & the Simulation Engine

You highlighted two foundational mechanics:

1. *"Actions have a time that ticks down. So if I wanted to get somewhere, I'd
   need to mine some ore that might take 3 minutes and you can queue a few
   actions that can be done. I think this should be lazily evaluated."*
2. *"How do people do commands, and how they are handled by the simulation."*

This document traces the exact math of **Lazy Evaluation** in a shared
multiplayer world, walks a command from network bytes to world mutation, and
shows how the same pipeline unlocks hyper-fast Genetic Algorithm training later.

______________________________________________________________________

## 1. Lazy Evaluation: Doing It Right in a Shared World

### 1.1 Where Naive Read-Only Lazy Evaluation Shines — and Where It Breaks

In a purely solo browser game (like Melvor Idle), lazy evaluation is trivial:
you record `started_at = 12:00:00`, `duration = 180s`, and do **nothing** until
the player refreshes at `12:10:00`, at which point you compute
`floor(600 / 180) = 3 ores mined`.

In **Ironwood Wilds**, two things cross Household boundaries:

1. **Shared Depletable Wilderness Nodes:** Suppose Bog-Iron Seam `#88` has **1
   ore remaining**. Aldric (Household A) starts a 3-minute `MINE` at `12:00:00`
   (finishes `12:03:00`). Bot-7 (Household B) starts a 3-minute `MINE` at
   `12:01:00` (finishes `12:04:00`).
   - If Household A closes their laptop until `12:30:00`, while Bot-7 polls at
     `12:04:01`, **naive per-player read-time evaluation would evaluate Bot-7
     first and award Bot-7 the last ore — violating time causality!**
2. **Multi-Entity Synchronized Events:** A 3-character Party delving a Barrow
   together, or a Hold Raid striking at `12:15:00`, affects multiple entities
   simultaneously.

### 1.2 The Solution: Hybrid Lazy State + Causal Event Min-Heap

We combine **O(1) timestamp-derived continuous values** with an **in-memory
Causal Event Min-Heap** (`SamWildsEventHeap`) keyed by
`(due_ms, sequence_tiebreaker)`:

```
Time ────────────────────────────────────────────────────────────────────────►
      12:00:00                12:03:00         12:04:00              12:15:00
         │                       │                │                     │
  Aldric starts MINE      [Heap Event #1]  [Heap Event #2]       [Heap Event #3]
  (0 CPU for 180 sec!)    Aldric completes Bot-7 completes       Hold #4 Raid
                          → gets last ore! → node empty,         resolves!
                          → starts next      fail-soft advances
                            queued action    Bot-7's queue
```

#### What Is Computed Lazily by Formula (Zero Scheduled Ticks!)

- **Action Countdown Progress:**
  `remaining_ms = (due_ms > now_ms) ? (due_ms - now_ms) : 0`. Never ticked per
  second! Clients compute smooth progress bars locally from `started_ms` and
  `due_ms`.
- **Stamina / Rest Recovery:** Store `stamina_anchor_val` + `stamina_anchor_ms`
  \+ `regen_rate_per_min`. Current stamina at any `now_ms` is
  `min(max_stamina, anchor_val + (now_ms - anchor_ms) * rate)`! Only re-anchored
  when stamina rate changes (e.g., starting heavy labor or eating rations).
- **Building Weathering / Condition:** Derived from
  `(now_ms - last_maintained_ms) * decay_rate`, re-anchored on `REPAIR` or Raid
  damage.
- **Wilderness Resource Regrowth:** Depleted pine groves store `regrows_at_ms`.
  Checking if choppable at `now_ms` is a single integer comparison
  `now_ms >= site->regrows_at_ms`!

#### What Enters the Causal Event Min-Heap

Only **discrete state transitions** that mutate inventories, XP, queues, or
shared nodes:

1. `EVENT_CHAR_ACTION_DUE` (at most **1 heap entry per active character** — 500
   active characters = a 500-element binary heap, ~8 KB in L1 cache!)
2. `EVENT_PARTY_ROUND_DUE` (1 entry per active PvM battle)
3. `EVENT_HOLD_RAID_DUE` (1 entry per Hold's next scheduled raid check)
4. `EVENT_HOMELAND_DISPATCH_DUE` (1 global entry for shifting Tribute war
   demands)

#### Drift-Free Queue Chaining

When `samwilds_advance_until(world, now_ms)` runs:

```c
while (!heap_empty(&w->heap) && heap_peek(&w->heap).due_ms <= now_ms) {
  SamWildsScheduled ev = heap_pop(&w->heap);
  /* Skip cancelled/superseded timer tokens via generation check */
  if (!scheduled_still_valid(w, ev)) continue;

  /* Resolve strictly at ev.due_ms — NOT wall-clock now_ms! */
  resolve_scheduled_event(w, ev, ev.due_ms);
  /* If actor has a queued next action, its started_ms = ev.due_ms,
     and its new due_ms = ev.due_ms + next_duration_ms is pushed to heap! */
}
w->clock_ms = now_ms;
```

Notice the magic: if Aldric queued three 3-minute actions at `12:00:00` and
nobody talked to the server until `12:10:00`, the heap pops `12:03:00`, chains
the second action (`12:03:00 → 12:06:00`), pops `12:06:00`, chains the third
(`12:06:00 → 12:09:00`), pops `12:09:00`, and leaves Aldric `IDLE` as of
`12:09:00` with **zero timer drift** and **exact causal ordering** against every
other player in the world!

#### Bonus Superpower: 1,000× Speed Headless Simulation for Genetic Algorithms

Because nothing loops `for (sec = 0; sec < 86400; sec++)`, a headless soak test
or `samgenai` fitness evaluation simply jumps
`w->clock_ms = heap_peek(&w->heap).due_ms` from event to event. Simulating an
entire week of Household play takes **a few thousand heap pops (~2 milliseconds
of CPU)** instead of 604,800 one-second ticks!

______________________________________________________________________

## 2. End-to-End Lifecycle of a Command

Whether a human clicks **"Queue: Mine Bog-Iron ×3"** in Chrome or a Python bot
sends JSON over TCP, the pipeline has six deterministic stages:

```
 [Client Wire] ──► (1) Decode & Auth ──► (2) Ownership Check ──► (3) World Catch-Up
                                                                         │
 [Client Resp] ◄── (6) Emit & Reply  ◄── (5) Schedule Heap   ◄── (4) Enqueue Validate
```

### Stage 1 & 2: Transport Decode, Auth & Tenancy Gate (App Layer)

- Transport adapter parses HTTP `POST /api/v1/characters/chr-19/queue` (or
  WS/TCP frame) into a `SamWildsCommand` inside the request's scratch arena.
- Auth middleware resolves `account_id` → `household_id`.
- **Tenancy check:** Verifies `character.household_id == caller.household_id`
  before the engine ever sees the command. You can never inspect or command
  another player's retainers.

### Stage 3: Causal World Catch-Up

- Acquire world lock; call `samwilds_advance_until(world, wall_clock_ms())`.
- All world completions up to this millisecond are now settled.

### Stage 4: Two-Phase Validation (Enqueue Hard-Fail vs. Start Fail-Soft)

Adapted directly from `samvillage`'s proven §1.3 P4 rule, extended for queued
travel & shared frontier nodes:

1. **Enqueue-Time Validation (Immediate `409 Conflict` if nonsensical):**
   - Is the character alive and not locked inside an active PvM battle?
   - Does the queue have room? (`pending_count < 4`, where each slot can hold
     `batch_count` 1..10 of a repeatable action).
   - Does the character meet the **static** prerequisites (e.g., Mining Level ≥
     15 for Copper, Household Tech unlocked for `SMELT_STEEL`, target site is
     known/surveyed by this Household)?
   - *Note on chained location checks:* Enqueue validation projects the
     character's **planned location** across their existing queue! So you *can*
     queue `[1] TRAVEL → Bog Fen`, `[2] MINE_BOG_IRON × 5`, `[3] TRAVEL → Hold`,
     `[4] DEPOSIT_ALL` in one click while standing at the Hold!
2. **Start-Time Validation (Fail-Soft Advance when the action's turn actually
   arrives):**
   - Ten minutes later, when `[2] MINE_BOG_IRON` actually starts, the world may
     have changed (another Household depleted the seam, your pickaxe broke, or
     your pack is already full).
   - Instead of crashing or wedging the character forever, `validate_start`
     emits an `ACTION_SKIPPED` event (`reason = NODE_DEPLETED` or `PACK_FULL`)
     to your Household's event log and **advances cleanly to
     `[3] TRAVEL → Hold`** so your gatherer still walks home with whatever they
     already mined!

### Stage 5: Batching, Interrupts & Cancellation Rules

How do repeatable batches (`MINE × 5`) and cancellations behave fairly?

- **Granular Batch Execution:** `MINE × 5` (at 60s per ore) is **not** one
  monolithic 300s lock — it executes as 5 consecutive 60s strikes
  (`remaining_reps` decrements 5 → 4 → 3 → 2 → 1 → 0), granting XP and 1 ore
  after *each* 60s strike.
- **Replacing / Clearing the Queue (`CLEAR_QUEUE` / `CANCEL_ACTIVE`):**
  - Pending slots clear instantaneously at zero cost.
  - Cancelling an **in-progress** gathering/crafting action forfeits the partial
    progress of the *current* rep (no 99%-cancel exploit; materials consumed at
    completion or refunded per rule), leaving completed prior reps in the
    character's pack.
  - **Travel & PvM Lock:** Cancelling `TRAVEL` turns the character around
    (`RETURN_HOME` taking the elapsed travel time back so you cannot teleport!).
    Inside a `PVM_DELVE`, individual queue edits are disabled — you trigger the
    Party's `ORDER_RETREAT` command instead, which resolves on the next combat
    round boundary.

______________________________________________________________________

## 3. Concrete Worked Example: A 4-Step Queued Expedition

Suppose **Aldric** (`chr-4`, Woodcutting Lv 12, Iron Axe equipped) is `IDLE` at
**The Hold** (`site-1`) at `t = 1000.0s`. You submit:

```json
POST /api/v1/characters/chr-4/queue
{
  "mode": "append",
  "actions": [
    { "type": "TRAVEL",    "target": "site-9" },
    { "type": "CHOP_WOOD", "target": "site-9", "count": 3 },
    { "type": "TRAVEL",    "target": "site-1" },
    { "type": "UNLOAD_TO_HOLD" }
  ]
}
```

1. **`t = 1000.0s` (Command Arrives):**
   - Planned-location trace: `site-1` → (`TRAVEL`) → `site-9` → (`CHOP` at
     `site-9` ✓) → (`TRAVEL`) → `site-1` → (`UNLOAD` at Hold ✓).
   - Action `[0] TRAVEL → site-9` starts immediately! Distance = 1.2 leagues,
     Wayfaring Lv 5 → `duration = 90,000 ms`.
   - Heap inserts `(due_ms = 1,090,000, actor = chr-4, token = 71)`.
   - HTTP returns `200 OK` with Aldric's updated queue and
     `active.due_ms = 1090000`.
2. **`t = 1090.0s` (Heap Pops Token 71):**
   - Aldric's `location_id` updates to `site-9` (Whispering Pine Barrens); +8
     Wayfaring XP; emits `ARRIVED` event.
   - Queue pops `CHOP_WOOD × 3`. Strike #1 validates (trees > 0, pack space ≥ 1)
     and schedules `due_ms = 1,135,000` (45s at Woodcutting Lv 12).
3. **`t = 1135.0s, 1180.0s, 1225.0s`:**
   - Strikes #1, #2, #3 complete; +3 Pine Logs in Aldric's pack; +45 Woodcutting
     XP (levels up to Lv 13 on strike #3 → emits `LEVEL_UP` event!).
   - Queue advances to `TRAVEL → site-1` (`due_ms = 1,315,000`).
4. **`t = 1315.0s` → `t = 1320.0s`:**
   - Aldric arrives at the Hold, runs 5s `UNLOAD_TO_HOLD`, deposits 3 Pine Logs
     into Hold Stockpile, and transitions cleanly to `STATE_IDLE` (`due_ms = 0`,
     zero heap entries, zero CPU until next order).

______________________________________________________________________

## 4. How Commands Bridge to Future Genetic Algorithms (`samgenai`)

Because `libs/samwilds` is headless and deterministic, plugging in `libs/samvm`
\+ `libs/samgenai` later requires **zero changes to simulation mechanics**:

```
SamWildsHouseholdView (fogged) ──► feature_encoder() ──► float inputs[32]
                                                               │
                                                         samvm_run(genome)
                                                               │
SamWildsCommand (standard!)    ◄── action_decoder()  ◄── float outputs[12]
```

- Whenever a character in a GA-controlled Household goes `IDLE` (or a
  Raid/Dispatch alert fires), the harness encodes that character's skills,
  stamina, Hold stockpile ratios, and Homeland war multipliers into `samvm`'s
  input registers, runs the evolved program, decodes the highest-scoring legal
  `(ActionType, TargetCategory)` output into a standard `SamWildsCommand`, and
  calls `samwilds_submit_command`.
- Fitness = `total_tribute_favor + hold_tier_bonus - casualty_penalty` after 30
  simulated days (~15 ms of headless runtime per episode!).
