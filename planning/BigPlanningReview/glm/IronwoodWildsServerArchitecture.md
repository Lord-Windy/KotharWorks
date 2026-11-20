# Ironwood Wilds — Server Architecture (IronwoodWildsServerArchitecture.md)

> Companion docs: [Gameplay](IronwoodWildsGameplay.md) ·
> [Commands & Simulation](IronwoodWildsCommandsAndSimulation.md) ·
> [Procgen](IronwoodWildsProcgen.md) · [Web](IronwoodWildsWebInterface.md) ·
> [Accounts & Persistence](IronwoodWildsAccountsAndPersistence.md) ·
> [Prototype Plan](IronwoodWildsPrototypePlan.md)

## 1. The one-sentence architecture

A **deterministic simulation kernel in a library** (`libs/samwilds`) that
knows nothing about networks or clocks, wrapped by a **thin hexagonal
server app** (`apps/samwilds`) that adapts HTTP, PostgreSQL, and the wall
clock onto it — the samvillage shape (TRD §1.2, §3.4), scaled from one
village to a persistent multi-household world.

This is not a new idea for this repo — it is the documented next step:
samvillage TRD §1.1 phase 2 ("persistent hosted world, multiple players,
real bots running 24/7"). Ironwood Wilds is that phase, with a name, a
map, and a tech tree.

## 2. Module map (fits the ptah conventions)

```
libs/samterra   (new)  seeded field & worldgen: noise, biomes, hydrology,
                       site placement, name grammar. No game rules.
libs/samwilds   (new)  the game engine: ECS store, systems pipeline,
                       action heap, rules tables, combat resolvers,
                       worldgen driver (consumes samterra), journal
                       codec, snapshot save/load. Headless, deterministic,
                       arena-first, zero I/O.
libs/samws      (later, optional) WebSocket server on samplex — see §9.

apps/samwilds   (new)  the server binary: composition root. samhttp +
                      samplex front end, libpq persistence adapter,
                      auth, event fan-out, admin console. Thin on rules.
apps/samwilds-bot (new) policy bot playing over HTTP only (libcurl+cjson,
                      no engine link — the samvillage-bot rule: the play
                      pattern is the thing under test).
apps/samwilds-cli (opt) human CLI client; the web UI may make it redundant.

foundation: samrena (arenas) · samdata (hash/set/RNG) · samstring ·
            samhttp/samhtml/samplex (web stack) · cjson (wire+persistence)
```

Why `samterra` is split from `samwilds`: samsimulator.md already wants
"seeded, deterministic, consulted" land fields; procgen_mvp wants
generative text content. Terrain/worldgen math is a reusable foundation —
game rules are not. Splitting keeps both futures open and matches the
repo's one-concern-per-lib discipline.

Dependency rule (enforced by taste and CMake, as everywhere in ptah):
samterra and samwilds contain **no** libpq, libcurl, or pthreads
dependencies. Anything that smells of I/O lives in apps/ or behind a port.

## 3. Process and threading model

One process, one world (per running season), three thread kinds:

```
            ┌──────────────────────────────────────────────────┐
 reactor(s) │ samplex io_uring threads: HTTP requests in/out   │
            └──────────────┬───────────────────────────────────┘
                           │ parse+auth+shape-check only
                           ▼
                 command inbox ──────────────┐  (mutex + cond,
                 (queue of validated-enough  │   samvillage world-mutex
                  SamWildsCommand, batched    │   pattern, generalized)
                  per tick)                  │
                           ┌─────────────────┘
                           ▼
            ┌──────────────────────────────────────────────────┐
 sim thread │ THE tick (1 Hz, wall-derived):                   │
 (1 thread, │  1. drain inbox (deterministic order, §4)        │
  owns the  │  2. pop due action completions (heap)            │
  world)    │  3. run systems pipeline (fixed order)           │
            │  4. emit events to outbox (seq-ordered)          │
            │  5. maybe snapshot (cadence)                     │
            └──────────────┬───────────────────────────────────┘
                           ▼
                 event outbox ──► journal writer (libpq, async)
                             ──► live fan-out (poll responders / later WS)
                             ──► projection updater (leaderboard tables)
```

- **Single-writer world**: only the sim thread touches world state.
  samvillage already runs tick-thread + world-mutex; this generalizes it
  with an explicit inbox/outbox so adapters never block the tick.
- HTTP threads never read world state directly either: reads are served
  from the last tick's **published view** (an immutable snapshot of the
  queryable state produced at end-of-tick — a read cache, not a second
  truth). One world, one writer, N cheap readers.
- The inbox is where fairness lives: commands received during tick N are
  applied at the start of tick N+1 in a **deterministic order** (see
  IronwoodWildsCommandsAndSimulation.md §4). No first-packet-wins races,
  no lock-ordering puzzles.

Why not multi-threaded simulation: 200 households × 12 folk = 2,400
actors whose actions are lazy timestamps (O(due) per tick, not
O(population)). The tick will be microseconds-to-milliseconds for years.
Determinism is worth infinitely more than cores right now — and
samgrognard already proved how far in-house determinism discipline pays
(records, replay, forks, goldens, WASM parity).

## 4. Determinism contract

Same season seed + same ordered command journal + same rules version ⇒
byte-identical world state hash. (samgrognard's record/hash-chain
discipline and samvillage's `world_hash` golden are the precedents; steal
both.) Concretely, in samwilds:

1. All rules math in **integers or fixed-point** (samgrognard is
   integer-only by design; f32 only where samvm-style determinism is
   already proven — nowhere in v1).
2. One world RNG with **named substreams** (worldgen / combat / events),
   each a seeded `SamRng`; the RNG state is part of the snapshot and the
   stream replays on load (samvillage §7 does exactly this).
3. Wall time enters **only** as tick numbers at the app boundary; the
   engine sees "tick N", never `clock_gettime`.
4. Commands apply in a defined order at tick boundaries (§3 above).
5. World state hash + journal digest published per tick in the admin
   console — drift alarms early.

What determinism buys Ironwood Wilds specifically: crash recovery
(snapshot + replay journal tail), cheat forensics (a disputed week
re-derives), **content** (any interval of any season is replayable —
timelapses and "what happened here?" pages are free), and the phase-3
genetic-algorithm harness (a headless season replayed at max speed is a
fitness function; samgenai's interactive-environment problem kind is
already specced for exactly this).

## 5. Inside the engine: ECS, not object trees

**Decision: entity–component–system, sparse-set storage.** The question
"Command/Strategy/Prototype or entity schema" is really two questions —
*how intents enter* (command pattern) and *how state is shaped* (ECS) —
and the answers compose:

### 5.1 Entities and components

- Entity = `uint64_t` (32-bit index + 32-bit generation — dead ids can't
  be resurrected by accident).
- Component = a plain struct registered by type id; storage is a sparse
  set (index→slot map + dense array). Iteration walks dense arrays
  (cache-friendly, arena-resident, trivially serializable — component
  arrays become snapshot sections nearly verbatim).
- No archetypes (Flecs-style) in v1: sparse sets are simpler in C,
  and with 2.4k actors the iteration difference is noise. Note it as a
  measured upgrade if profiling ever disagrees.

```c
typedef uint64_t SamWildsEntity;

typedef struct { uint8_t x, y; uint8_t region; } CPosition;
typedef struct { uint16_t hunger, energy; }       CNeeds;
typedef struct { SamWildsAction action;           } CBusy;      // running only
typedef struct { SamWildsQueue  queue;            } CQueue;     // pending
typedef struct { uint8_t level[SKILL_COUNT]; uint32_t xp[SKILL_COUNT]; } CSkills;
typedef struct { SamWildsStack slots[12];         } CInventory;
typedef struct { uint32_t hp, max_hp; uint8_t wounds; } CHealth;
/* household, claim, building-part, deposit, beast, party, crate, ... */
```

People, trees, veins, walls, beasts, crates, banners are all entities;
"what a thing is" is which components it carries. New behaviour = new
component + new system, no inheritance surgery — the extensibility win
OOP gives via composition, in data.

### 5.2 Systems

A system is `void (*fn)(SamWildsWorld *w, uint64_t tick)` registered in
a **fixed, table-driven order** at boot. v1 pipeline sketch:

```
needs-decay → queue-start (validate+begin due actions) → action-complete
→ production/gather yields → construction accumulation → combat
(expeditions/raids) → beast ecology → claim/decay → birth-of-events
```

The registry is data (name → fn → declared read/write component sets,
unchecked-but-documented in v1). Adding a season mechanic = adding a
system to the table; the tick loop never changes.

### 5.3 Prefabs (the Prototype pattern, straight-faced)

Spawning is cloning a **prefab**: a serialized component bundle in the
rules data ("oak tree", "iron vein", "grey wolf", "settler", "palisade
section"). samterra's worldgen, raid spawner, and the migrant-ship event
all stamp instances from the same prototypes — one definition of "wolf",
ecology, raids, and the API agree. (This is also the genetics on-ramp:
a prefab with mutable trait fields is a genome with a body.)

### 5.4 Patterns → C translation table

Nothing here needs objects; every OO pattern Sam knows has a house C
equivalent already proven somewhere in ptah:

| OO pattern | C realization here | Precedent in repo |
|---|---|---|
| Command | `SamWildsCommand` tagged union + dispatch table of validators/appliers | samvillage action engine; samtasker service ops |
| Strategy | struct of function pointers | samtasker repository; samplex `SamplexReactor` |
| Prototype | prefab component bundles, stamped by spawner | samgrognard scenario setup |
| Factory/Registry | type-id → constructor tables (components, commands, systems, prefabs) | samgrognard bot policy registry (versioned!) |
| Observer | event bus: seq-ordered append-only stream + subscriber list | samvillage event log; samgrognard records |
| Repository | ports struct of fn pointers (inbox, event sink, world store, clock) | samtasker storage adapters |
| State machine | enum + transition table (person states, building lifecycle, season phases) | samtasker status table |
| Chain of responsibility | staged command validation pipeline (shape → authz → world rules) | samvillage enqueue/start two-phase |
| Flyweight | immutable rules/definition tables shared by all instances | samvillage §4 tables via `/v1/rules` |
| Facade | umbrella header (house rule) | every lib |
| Composition root | `apps/samwilds/main.c` wires ports → adapters | samdashboard, samtechtree |

## 6. Rules as data (extensibility where it pays)

samvillage D9/D16 (rules tables as engine constants + served via
`/v1/rules` + versioned) generalizes to a **two-tier rule store**:

- **Tier 1 — balance data** (Charter values, action durations, skill
  curves, tech nodes+prices, raid cadence, prefab stats): season-tweakable
  tables, loaded at season creation, hashed into the rules digest.
  Editing a season's economy = editing data, zero code.
- **Tier 2 — mechanics** (systems, command vocabulary): code, versioned
  (`SAMWILDS_RULES_VERSION` + engine revision stamped in every snapshot
  and journal, samgrognard-style).

Content cadence between seasons is therefore data work — the actual
bottleneck for a text game with one author.

## 7. The server app (hexagonal, per the house pattern)

`apps/samwilds` uses the samtrader/samdashboard/samtechtree layout:

```
apps/samwilds/
  domain/     view-models over the engine (world feed, person page,
              rankings) — pure, testable
  ports/      clock, world_store, journal_sink, broadcaster, mailer(no)
  adapters/   http (samhttp router), auth (argon2 + sessions), pg
              (snapshot/journal/projections), fanout, admin pages
  commands/   route handlers: shape-validate, authz, enqueue — no rules
  main.c      composition root: wire ports, boot engine thread, serve
```

The **only** module allowed to call the engine is the tick host (inbox
drain + view publish); commands/ never mutate, adapters/ never see
entities. This keeps the API surface (Web doc §3) honest: the HTTP layer
is genuinely replaceable — the WASM shell, a future MQTT gateway, or a
GA farm are all just other adapters around the same lib.

## 8. Operational shape

- One systemd unit per season process + PostgreSQL; static web assets
  from the same server (samstatic pattern) or a sibling. No node on the
  host (the tinysam decision stands).
- TLS: terminated by reverse proxy or Tailscale — samhttp stays
  plain-HTTP by design.
- Admin console (auth-gated pages): world hash + journal digest per
  tick, inbox depth, tick duration histogram, per-household activity,
  season lifecycle buttons (see Persistence doc §5).
- Crash recovery: last snapshot + journal tail replay (§4). Target RPO =
  one snapshot cadence (5 min) + journal replay to the second.

## 9. Transport ladder (decision)

1. **v1: REST/JSON + polling** — samvillage's proven shape; its bot
   polls ~1 Hz today. Events since `last_seq`, state via GET.
2. **v2: SSE or long-poll** for the live feed — requires chunked/streamed
   responses in samhttp (small, contained addition; `Connection: close`
   v1 behaviour stays for everything else).
3. **v3 (only if UX demands): `libs/samws`** WebSocket on samplex for
   the spectate feed and push ticks. Deferring WS is deliberate: every
   month on polling first is a month of gameplay data before spending
   Fridays on a protocol lib. The port boundary means no design decision
   changes when it lands.

## 10. Decision log

| # | Decision | Rationale / alternative rejected |
|---|---|---|
| A1 | Engine in `libs/samwilds`, server thin in `apps/samwilds` | samvillage D2 proven; keeps engine embeddable (tests, WASM, GA farm) |
| A2 | Procgen split out as `libs/samterra` | reusable by samsimulator/procgen_mvp; game rules out of worldgen |
| A3 | ECS sparse-set storage, table-driven systems | extensibility without inheritance; snapshot-friendly; archetype complexity rejected at this scale |
| A4 | Single sim thread, single-writer world, inbox/outbox | determinism >> cores at 2.4k lazy actors |
| A5 | Command-sourced determinism (journal + snapshots, state hash per tick) | replay/recovery/forensics/content/GA — the samgrognard dividend |
| A6 | Integer/fixed-point rules math | f32 determinism not worth fighting; samgrognard precedent |
| A7 | Two-tier rules (balance data vs mechanics code), both versioned+hashed | season content cadence without recompiles |
| A8 | Published per-tick view for HTTP reads | no reader locks on world state; one truth |
| A9 | Transport ladder: poll → SSE → WS-as-lib | earn the WS with usage data; port boundary absorbs the change |
| A10 | One process per season world | operational simplicity for a Fridays-only operator; shard = later, by the same inbox seam |
