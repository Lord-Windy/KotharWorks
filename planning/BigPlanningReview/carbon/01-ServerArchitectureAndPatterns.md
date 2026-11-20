# 01 — Server Architecture & Design Patterns in C11

You noted:

> *"I want this to be modularized as much as possible with a strong emphasis on
> design patterns for extensibility. I am not familiar with this land like I am
> with Object Orientated ones. Whether we use something like
> Command/Strategy/Prototype or we use Entity schema doesn't worry me but I want
> to see it."*

This document bridges **Object-Oriented Design Patterns** and **Data-Oriented /
Entity-Schema C11** directly inside Ptah's `samrena`-first style, then lays out
the server's library and process boundaries.

______________________________________________________________________

## 1. OOP vs. C11 Data-Oriented Patterns: Why Not Both?

In Java/C#/C++, extensibility usually means **inheritance hierarchies**
(`class IronMiner : public GatheringCharacter : public Entity`) and
heap-allocated pointer webs. In C11 — especially with `samrena` arenas,
deterministic saves, and PostgreSQL persistence — deep pointer graphs and
per-entity vtables fight the language:

1. Arenas hate random `new`/`delete` of heterogeneous sizes.
2. Pointer graphs cannot be hashed (`samvillage_world_hash`) or serialized
   cleanly to SQL/JSON without brittle pointer-swizzling.
3. Inheritance Tangling ("The Gorilla-Banana Problem"): what happens when a Hold
   Building also acts as a Combatant (a Ballista Tower) *and* a Storage
   Container?

In modern C simulation engines, you don't throw away Gang-of-Four patterns — you
**separate Data from Behavior**:

- **State** uses an **Entity Schema (Relational Tables in RAM)** +
  **Prototype/Flyweight Rule Descriptors**.
- **Behavior & Seams** use **Command**, **Strategy (Vtable + Cookie)**, and
  **Observer (Append-Only Event Log)**.

Here is what each looks like side-by-side in C11.

______________________________________________________________________

## 2. The Five Core Design Patterns of `samwilds`

### Pattern A: Prototype / Flyweight → Immutable `RuleDef` Tables

Instead of cloning heavy objects or hardcoding `if (item == IRON_ORE)`, every
"kind of thing" in the game is an immutable **Prototype Descriptor** loaded into
a read-only `rules_arena` at startup, addressed by a compact `uint16_t` ID.

```c
typedef uint16_t SamWildsItemDefId;
typedef uint16_t SamWildsBuildingDefId;
typedef uint16_t SamWildsActionDefId;

/* Flyweight / Prototype: 1 instance shared by 10,000 iron ingots */
typedef struct {
  SamWildsItemDefId id;
  const char *slug;           /* "bog_iron_ore", "ironwood_plank" */
  const char *singular;
  const char *plural;
  uint32_t unit_weight_g;     /* carry math */
  uint32_t base_tribute_pts;  /* Homeland base value */
  uint32_t item_flags;        /* EQUIP_MAINHAND | RESOURCE_ORE | VAULTABLE */
  uint16_t procgen_category;  /* hook into samprocgen text slots */
} SamWildsItemDef;
```

**Extensibility win:** Adding 40 new ores, planks, weapons, or monsters requires
**zero new C structs or code paths** — just rows in the rules catalog (served
verbatim at `GET /api/v1/rules`, matching `samvillage` D9).

______________________________________________________________________

### Pattern B: Entity Schema → Relational Arena Pools + Generational Handles

You faces three options for storing live entities in C:

| Approach                                                         | How It Works                                                                                               | Verdict for Ironwood Wilds                                                                                                                  |
| ---------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| **1. OOP Heap Structs + Pointers**                               | `Character *target;` scattered in RAM                                                                      | **Avoid.** Dangling pointers when a character dies in PvM; fights `samrena`; painful SQL/save mapping.                                      |
| **2. Archetype ECS** (Flecs/Bevy style)                          | Bitmask archetypes moving components across arrays on add/remove                                           | **Overkill.** Built for 1M particles at 60 FPS SIMD. Makes relational queries ("all characters in Household 7 at Site 12") awkward.         |
| **3. Relational Entity Schema (Typed Pools + Generational IDs)** | In-memory tables (`households[]`, `characters[]`, `holds[]`, `sites[]`, `parties[]`) linked by integer IDs | **Sweet spot.** Reads like SQL in C, maps 1:1 to PostgreSQL, zero `malloc`/`free`, deterministic iteration order, immune to use-after-free. |

#### Generational Handles (`SamWildsId`)

When Character #42 dies in a Barrow and Settler #42 reuses that pool slot an
hour later, any stale queued command or bot reference still holding the old ID
must fail cleanly instead of commanding the wrong person:

```c
typedef struct {
  uint32_t index;      /* slot index in pool array (O(1) lookup) */
  uint32_t generation; /* bumped every time the slot is freed */
} SamWildsId;          /* packs cleanly into a uint64_t for SQL / JSON */
```

#### Solving `samrena` Churn in a 24/7 Server: Pool Free-Lists

`samrena` is a bump allocator. If a server runs for weeks, how do characters
die, parties disband, and depleted sites despawn without leaking memory?
**Fixed-stride pools backed by `SamrenaVector` + an embedded free-list:**

```
world_arena (Samrena)
  └── CharacterPool.slots[] : [Alive gen=1][Alive gen=3][FREE next=5 gen=2][Alive gen=1]...
                                                              │
                                            free_head ────────┘
```

- When a pool needs capacity, `SamrenaVector` grows cleanly inside
  `world_arena`.
- When an entity dies, its `generation` increments and its `index` pushes onto
  `free_head` — **zero `free()`, zero memory growth under steady-state churn**.
- Fixed-bound sub-arrays (e.g., `SamWildsQueuedAction queue[SAMWILDS_MAX_QUEUE]`
  directly inside `SamWildsCharacter`) eliminate tiny per-action allocations
  completely.

______________________________________________________________________

### Pattern C: Command Pattern → Tagged Value Struct + Handler Registry

Every mutation — whether clicked in a browser tab, `POST`ed via curl, sent over
a raw bot socket, or chosen by a `samgenai` genome — becomes the **exact same
plain-data `SamWildsCommand` struct** dispatched through a table of command
handlers:

```c
typedef enum {
  SAMWILDS_CMD_NONE = 0,
  SAMWILDS_CMD_ENQUEUE_ACTION,   /* TRAVEL, CHOP, MINE, CRAFT, BUILD, REST... */
  SAMWILDS_CMD_REPLACE_QUEUE,
  SAMWILDS_CMD_CLEAR_QUEUE,
  SAMWILDS_CMD_EQUIP_ITEM,
  SAMWILDS_CMD_CONSTRUCT_HOLD_BLD,
  SAMWILDS_CMD_RESEARCH_TECH,
  SAMWILDS_CMD_SET_RAID_DOCTRINE,
  SAMWILDS_CMD_FORM_PARTY,
  SAMWILDS_CMD_SHIP_TRIBUTE,
  SAMWILDS_CMD_RECRUIT_SETTLER,
  SAMWILDS_CMD_COUNT
} SamWildsCommandKind;

/* Command Handler Vtable — one row per SamWildsActionType / CommandKind */
typedef struct {
  const char *name;
  SamWildsRejectCode (*validate_enqueue)(const SamWildsWorld *w, const SamWildsActionSpec *spec);
  SamWildsRejectCode (*validate_start)(const SamWildsWorld *w, SamWildsId actor, const SamWildsActionSpec *spec);
  uint64_t           (*compute_duration_ms)(const SamWildsWorld *w, SamWildsId actor, const SamWildsActionSpec *spec);
  void               (*apply_complete)(SamWildsWorld *w, SamWildsId actor, const SamWildsActionSpec *spec, uint64_t now_ms);
  void               (*on_interrupt)(SamWildsWorld *w, SamWildsId actor, const SamWildsActionSpec *spec, uint64_t now_ms);
} SamWildsActionVtable;
```

**Why OOP veterans love this in C:** Adding a new `SMELT_CRUCIBLE` or
`BREW_POULTICE` verb never touches the scheduler, networking, queue logic, or
persistence. You implement its 4 small callbacks (or reuse generic
`recipe_action_vtable` driven by `SamWildsRecipeDef`!) and register the row.

______________________________________________________________________

### Pattern D: Strategy Pattern → Function-Pointer Struct + `void *ctx`

Ptah already uses this idiomatic C Strategy pattern in `SamtaskerRepository`
(`libs/samtasker`) and `SamplexReactor` (`libs/samplex`). We apply it at the
**four genuine variation seams**:

1. **Controller Strategy (`SamWildsControllerOps`):** Human inbox vs. Scripted C
   Bot Policy vs. `samgenai` VM Genome Evaluator.
2. **Combat / Raid Doctrine Strategy (`SamWildsDoctrine`):** Parameterized
   target-priority + retreat-threshold + vault-lock evaluators executed
   deterministically during PvM delves and Hold raids.
3. **Persistence Port (`SamWildsStorePort`):** In-memory mock store (hermetic
   unit tests) vs. JSON snapshot file (local offline dev / golden tests) vs.
   `libpq` PostgreSQL adapter (production server).
4. **Transport Adapter:** `samhttp` REST/HTML vs. `samplex` WebSocket vs.
   `samplex` raw newline-JSON TCP bot port.

```c
/* Strategy Pattern in C11: Operations table + opaque implementation pointer */
typedef struct {
  bool (*save_checkpoint)(void *ctx, const SamWildsDirtyBatch *batch, Samrena *scratch);
  bool (*load_world)(void *ctx, SamWildsWorld *out_world, Samrena *scratch);
  bool (*append_events)(void *ctx, const SamWildsEvent *events, size_t count);
} SamWildsStoreOps;

typedef struct {
  const SamWildsStoreOps *ops;
  void *ctx;
} SamWildsStorePort;
```

______________________________________________________________________

### Pattern E: Observer / Event Sourcing Light → Monotonic Event Stream

Instead of scattered UI/webhook callbacks tangled inside combat or mining math,
state transitions append structured `SamWildsEvent` records to an arena
ring-buffer (and flush asynchronously to `wilds.events` in Postgres):

- Each event carries `uint64_t seq`, `uint64_t timestamp_ms`,
  `SamWildsId household_id` (audience scope, `0` = global dispatch),
  `SamWildsEventType type`, and typed payload fields.
- Web tabs, SSE/WebSocket pushers, bot cursors (`GET /api/v1/events?since=seq`),
  procedural prose generators, and season analytics all **observe** this single
  ordered stream without coupling to simulation internals.

______________________________________________________________________

## 3. High-Level Module & Hexagonal Server Architecture

Following Ptah's `AGENTS.md` rule (*plain modules inside `libs/`, hexagonal
ports/adapters inside `apps/`*):

```
┌────────────────────────────────────────────────────────────────────────────┐
│                         CLIENTS (Humans & Bots)                            │
│   Browser Multi-Tab UI  │  samwilds-cli  │  samwilds-bot  │  3rd-Party AI  │
└────────────┬────────────┴───────┬────────┴───────┬────────┴────────┬───────┘
             │ HTTP/HTML+JSON     │ REST JSON      │ REST / Raw TCP  │
             ▼                    ▼                ▼                 ▼
┌────────────────────────────────────────────────────────────────────────────┐
│                   apps/samwilds-server  (Hexagonal Host)                   │
│                                                                            │
│  ┌───────────────────────── Driving Adapters ───────────────────────────┐  │
│  │  http_api.c (samhttp)  │  web_ui.c (samhtml)  │  bot_tcp.c (samplex) │  │
│  └─────────────────────────┬────────────────────┬───────────────────────┘  │
│                            ▼                    ▼                          │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │            commands/ & application/ (Auth Gate + Inbox)              │  │
│  │  • Validates session cookie or Bearer API key via AuthPort           │  │
│  │  • Enforces Household ownership ("does acct X own character Y?")     │  │
│  │  • Pushes SamWildsCommand into WorldService / reads fogged Snapshot  │  │
│  └──────────┬─────────────────────────┬──────────────────────┬──────────┘  │
│             │                         │                      │             │
│             ▼                         ▼                      ▼             │
│  ┌─────────────────────┐  ┌───────────────────────┐  ┌──────────────────┐  │
│  │  libs/samwilds      │  │   libs/samprocgen     │  │ Driven Adapters  │  │
│  │  Pure C11 Sim Core  │─►│   World Math &        │  │ • pg_auth.c      │  │
│  │  • Entity Pools     │  │   Slot/Template Prose │  │ • pg_wilds.c     │  │
│  │  • Lazy Event Heap  │  │   (uses samstring)    │  │ • pg_meta.c      │  │
│  │  • Skills/Tech/PvM  │  └───────────────────────┘  │   (libpq-fe)     │  │
│  └─────────────────────┘                             └────────┬─────────┘  │
└───────────────────────────────────────────────────────────────┼────────────┘
                                                                ▼
                                                     ┌─────────────────────┐
                                                     │     PostgreSQL      │
                                                     │ auth · wilds · meta │
                                                     └─────────────────────┘
```

### Internal File Layout of `libs/samwilds`

Flat, readable C11 modules under `libs/samwilds/src/`, exposed through one
umbrella `include/samwilds.h` (with optional clean subheaders
`include/samwilds/*.h`):

- `rules.c` — Static & data-loaded `RuleDef` catalogs + hypothesis config
  validation
- `pools.c` — Generational ID pools (`households`, `characters`, `holds`,
  `sites`, `parties`)
- `scheduler.c` — Min-heap causal event queue + lazy timestamp catch-up math
- `actions.c` — Enqueue/start/complete pipeline + `SamWildsActionVtable`
  registry
- `skills_tech.c` — OSRS XP curve math, recipe requirements, Household tech
  unlocks
- `hold_raids.c` — Construction, decay, vault protection, pre-planned Raid
  resolver
- `pvm_combat.c` — Party formation, round-based PvM CRT/combat, retreat &
  permadeath
- `tribute.c` — Homeland war dispatches, Favor calculation, settler recruitment
- `observe.c` — Builds fogged `SamWildsHouseholdView` & `SamWildsCharacterView`
  structs (enforcing information hiding by construction, just like
  `samgrognard_observe`)

______________________________________________________________________

## 4. Concurrency, Threading & Arena Discipline

Building on the proven pattern in `apps/samvillage/src/server.c` and
`libs/samplex`:

1. **I/O Thread (`samplex` `io_uring` Engine):** Accepts HTTP and socket
   connections, parses requests in **per-connection chain arenas**
   (automatically destroyed when the response finishes — zero leaks).
2. **Single-Writer World Serialization:** `SamWildsWorld` is guarded by a single
   coarse mutex (or lock-protected command ring). Because advancing the event
   heap for completed actions takes **microseconds** (pure integer RAM math,
   zero SQL inside the lock!), HTTP handlers acquire the lock, advance due
   events up to `now_ms`, apply the command or copy out the view struct into the
   **connection's arena**, and release the lock *before* rendering HTML/JSON or
   touching sockets.
3. **Background Persistence Worker:** Every `N` seconds (e.g., 5–15s) or on
   high-value checkpoints (recruitment, tech unlock, graceful `SIGTERM`), a
   background thread swaps out the dirty entity/event batch into a staging
   buffer and commits a single batched `libpq` transaction **outside** the
   simulation lock — so slow disk I/O never stalls HTTP tabs or bot ticks.

### Three-Arena Memory Guarantee

| Arena                       | Lifetime              | Contents                                                                  | Reset Policy                                    |
| --------------------------- | --------------------- | ------------------------------------------------------------------------- | ----------------------------------------------- |
| `rules_arena`               | Entire process        | Rules tables, procgen templates, tech tree                                | Never reset; destroyed at shutdown              |
| `world_arena`               | Entire season         | Fixed-stride entity pools + free-lists, event heap, event ring            | Steady-state 0 growth via slot recycling        |
| `conn_arena` / `step_arena` | Single request / step | `SamHttpRequest`, `samprocgen` prose strings, `samhtml` DOM, JSON buffers | Destroyed/reset immediately after response/step |
