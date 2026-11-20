# Ironwood Wilds — Server Architecture

## 1. Architectural goal

Build a **modular monolith with a replaceable edge**, not a collection of
services. The simulation is a headless C library; the hosted server supplies
identity, transport, scheduling wake-ups, durability, and presentation; every
client is external to the engine.

The important boundaries are:

1. **Authoritative core versus adapters.** The core never performs network,
   database, filesystem, locale, or wall-clock I/O.
2. **Application versus core.** The application authenticates, authorises,
   admits commands, commits durable effects, and chooses the current logical
   time. The core validates game rules and produces state transitions.
3. **Observation versus presentation.** The core/application returns structured,
   audience-safe facts. HTML and JSON adapters choose how to display them.
4. **Live world versus experiments.** Genetic training and generator experiments
   use isolated worlds and never connect to the live writer except as ordinary
   clients.

## 2. Proposed Ptah layout

```text
libs/samwilds/
├── CMakeLists.txt
├── include/
│   ├── samwilds.h
│   └── samwilds/
│       ├── model.h
│       ├── rules.h
│       ├── commands.h
│       ├── actions.h
│       ├── agenda.h
│       ├── worldgen.h
│       ├── combat.h
│       ├── observation.h
│       └── record.h
├── src/
│   ├── world.c                 # lifecycle and world invariants
│   ├── model.c                 # stable IDs and entity collections
│   ├── rules.c                 # immutable definition registry validation
│   ├── command.c               # dispatch and common validation
│   ├── action.c                # queue state machine and handler registry
│   ├── agenda.c                # deterministic future-event queue
│   ├── advance.c               # lazy advancement
│   ├── inventory.c
│   ├── progression.c
│   ├── construction.c
│   ├── expedition.c
│   ├── raid.c
│   ├── worldgen_*.c
│   ├── observe.c               # audience-filtered structured views
│   └── record.c                # versioned snapshot/event encode/decode
└── test/

apps/samwilds-server/
├── CMakeLists.txt
├── content/base/               # declarative items, recipes, tech, biomes, etc.
├── migrations/
├── src/
│   ├── main.c                  # composition root; no rules
│   ├── application.c           # use cases and admission pipeline
│   ├── runtime.c               # one logical writer per loaded world
│   ├── ports/
│   │   ├── clock.h
│   │   ├── world_repository.h
│   │   ├── identity_repository.h
│   │   └── password_hasher.h
│   ├── adapters/
│   │   ├── http_api.c
│   │   ├── html_pages.c
│   │   ├── content_json.c
│   │   ├── postgres_world.c
│   │   ├── postgres_identity.c
│   │   └── monotonic_clock.c
│   └── auth.c
└── test/

apps/samwilds-cli/              # libcurl + JSON only; no samwilds link
apps/samwilds-bot/              # external scripted controller initially
apps/samwilds-lab/              # later: seeds, maps, replay, soak, verification
```

This is one new simulation library, not a new library for every design pattern.
Split `samwilds` only if an independent consumer and stable boundary actually
emerge. `samwilds-lab` can begin as commands in the CLI/server test tooling
rather than a full application.

### Dependency direction

```text
samrena
  └── samdata
        └── samwilds  (+ samstring; cjson for versioned record encode/decode)

samwilds ───────────────┐
samhttp/samplex/samhtml ─┼──> apps/samwilds-server ──> libpq, cjson
                         │
libcurl/cjson ───────────┼──> samwilds-cli / samwilds-bot
                         │
                         └── no dependency from samwilds back to any app/adapter
```

`samgenai` is deliberately absent from the production dependency graph.

## 3. Reuse from the existing repository

| Existing work                  | Reuse                                                                                                                     | Do not copy blindly                                                                                     |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| `samvillage`                   | Headless engine, external CLI/bot, action double-validation, fail-soft queue advance, versioned rules, save/load goldens  | Fixed ticks, one bearer token for the whole world, positionless actions, offline single-user privileges |
| `samgrognard`                  | Canonical records, stable event sequence, audience-safe observations, state hashes, replay/fork tests, versioned bot APIs | Its turn-based WEGO model and domain-specific complexity                                                |
| `samhttp`                      | JSON/HTML HTTP adapter and routing                                                                                        | WebSockets, keep-alive/SSE, TLS, async handlers: it does not currently provide these                    |
| `samplex`                      | Event-loop/reactor substrate and future timer/wake integration                                                            | Running game rules concurrently on worker threads                                                       |
| `samtasker` repository         | Function-pointer port plus implementation cookie, caller-selected arena lifetimes                                         | A repository interface for every tiny entity                                                            |
| `samdashboard` / `samtechtree` | Composition root, PostgreSQL adapter/schema ownership, server-rendered pages, self-tests over real sockets                | Cross-schema writes or ad-hoc migrations                                                                |
| `samgenai`                     | Later isolated environment/episode evaluation                                                                             | Genetic code in the live server                                                                         |

## 4. C-friendly design patterns

### 4.1 Command — tagged values plus a dispatch table

A command is data, not an HTTP request and not a class hierarchy:

```text
CommandEnvelope
  protocol_version
  command_id               // idempotency key
  world_id
  household_id             // established from authenticated membership
  type                     // stable enum/wire key
  expected_revision?       // optimistic precondition; aggregate scope set by command type
  payload                  // tagged union internally, JSON externally
```

A registry maps command type to `{parse shape, authorise, execute}`. Parsing and
authentication happen before the writer; authoritative authorisation and game
validation happen inside it. Adding a command adds a payload type, handler,
tests, and protocol documentation—not a new route-specific mutation path into
world internals.

### 4.2 Strategy — action and system handlers

Different timed behaviours implement the same small operation set:

```text
ActionOps
  validate_enqueue(world, character, request)
  start(world, action, effects)       // atomic checks, costs, reservations, due time
  complete(world, action, effects)
  cancel(world, action, reason, effects)
  estimate(world, character, request) // display only; never authoritative early mutation
```

Movement, gathering, crafting, research, construction, shipment, and recovery
can share the queue/scheduler while using different handlers. Combat rounds and
ecology events have their own scheduled-event handler registry; they do not need
to pretend to be character actions.

Registries are immutable after world load and are versioned with the ruleset.
Persist stable keys, never function pointers or array positions whose meaning
can silently change.

### 4.3 Prototype — immutable definitions, mutable instances

`ItemDef`, `RecipeDef`, `TechDef`, `BuildingDef`, `BiomeDef`, `MonsterDef`, and
`ActionDef` are prototypes. Runtime records contain a definition ID plus only
mutable state.

A declarative base content pack is loaded and strictly validated at startup,
then frozen in a world-lifetime arena. C handlers supply behaviour; JSON
supplies costs, prerequisites, names, durations, yields, tags, and handler keys.
This supports content creation without inventing a scripting language. The same
validated registry is served through `/v1/rules` and hashed into the world
record.

### 4.4 Entity model — explicit records, not a generic ECS

Start with concrete records:

- `SamwildsHousehold`
- `SamwildsCharacter` (including queue, skills, location, health)
- `SamwildsActionInstance`
- `SamwildsStructure`
- `SamwildsExpedition`
- `SamwildsShipment`
- `SamwildsRegion` / `SamwildsSite`
- `SamwildsAgendaEntry`

Use stable `uint64_t` world-local IDs in the engine. JSON exposes canonical
typed entity strings; clients treat every entity ID as opaque. The grammars are
explicit:

- account, world, and household IDs: canonical lowercase UUID strings;
- characters/actions/expeditions/shipments/structures: `chr-`, `act-`, `exp-`,
  `shp-`, `bld-` followed by the positive world-local decimal ID;
- generated sites: `site-<ascii-slug>-<decimal-slot>` derived from stable
  region/tile/slot inputs, with the string-to-internal-ID mapping persisted on
  materialisation;
- raw seeds, counters, revisions, event sequences, and logical timestamps:
  decimal strings at JSON boundaries.

Logical time and PostgreSQL `BIGINT` positions use non-negative signed 64-bit
ranges; the unsigned world seed is stored as text. Checked conversion rejects
values outside the selected range. Collections are arena-backed vectors plus ID
indexes. Never expose a pointer as an ID or retain pointers across collection
growth unless the storage guarantees stability.

Borrow the useful ECS ideas—stable IDs, data separate from behaviour, explicit
systems—without paying for type erasure, component queries, and generic
serialization before the workload needs them. Sparse minute-scale actions and
rich invariants are not an obvious ECS workload.

### 4.5 Domain events — explicit effects, not observer callbacks

A mutation returns an ordered effect bundle:

```text
MutationResult
  new_world_revision
  domain_events[]           // authoritative facts and state/scheduler effects
  projection_invalidations[]
  next_due_time
```

The core does not call PostgreSQL, send WebSockets, or invoke arbitrary
observers. The runtime persists the bundle and then updates
projections/publishes notifications. Subscribers cannot recursively mutate the
world.

Events have world ID, sequence, logical time, type, subject,
visibility/audience, and a versioned payload. Human-readable text is derived
from the payload, not used as the durable state representation.

### 4.6 Repository and Unit of Work — only at external seams

The application owns a small set of ports:

- world snapshot/journal loading and atomic commit;
- identity/session/token lookup;
- monotonic clock and timer wake-up;
- password hashing;
- optional content-pack loading.

A world commit is one unit of work: command result/idempotency record, domain
events/effects, scheduler changes, sequence counters, and projection revision.
Do not create `CharacterRepository`, `SkillRepository`, etc. with divergent
transaction boundaries.

### 4.7 Explicit state machines

Character actions, expeditions, shipments, structures, worlds, and accounts have
named states and legal transition functions. Examples:

```text
Action:    queued -> running -> completed
                    |    |-----> failed
                    |    |-----> cancelled
                    +---------> cancelled

World:     opening -> active -> closing -> archived

Shipment:  preparing -> in_transit -> arrived
                |----------> cancelled/lost (only if rules permit)
```

Invalid transitions return stable errors; they are not implicit boolean flips
scattered through handlers.

## 5. Runtime ownership and ordering

### Logical model

Each active world has one `SamwildsWorldRuntime` that owns:

- canonical in-memory state;
- future-event agenda;
- next entity/action/event sequence values;
- current logical time and committed revision;
- an ordered inbox for commands and maintenance requests.

Only this runtime calls `samwilds_advance` or `samwilds_apply_command`.
Transport workers may parse, rate-limit, and authenticate, but cannot mutate or
decide current game legality.

For an admitted command at logical time `T`:

1. Advance all due events through `T` in agenda order, committing bounded
   partial batches if necessary; do not execute the command until advancement
   reaches `T`.
2. Apply commands admitted for `T` in their assigned admission order.
3. Persist resulting effects atomically.
4. Publish the committed revision and response.

Once time has advanced past an event, no later request can be admitted
retroactively before it.

### Initial physical implementation

Keep the first version simple: execute the runtime on the same samplex/samhttp
engine thread. Requests are therefore naturally serialised. PostgreSQL calls are
synchronous and must be small and measured.

The server must implement a small **timer adapter**; samplex exposes low-level
timeout operations, not a ready-made earliest-deadline game scheduler. Its
contract is:

- on start/resume, anchor saved `logical_now` to the current monotonic host
  reading; only elapsed monotonic time advances the logical clock;
- arm a timeout for the earliest due event, and cancel/re-arm when a newly
  scheduled event is earlier;
- give each arming a generation; stale/cancelled wake-ups compare generation and
  current earliest deadline and become no-ops;
- distinguish an **admission pause** (catch-up/maintenance stops new commands
  while the clock may continue) from a **simulation-clock pause** (stop the
  timer and logical elapsed time);
- on a full pause, cancel/revalidate the outstanding wake; on resume, re-anchor
  `logical_now` to a fresh monotonic reading so paused duration creates no
  catch-up burst;
- on process restart, use the saved logical time and a new monotonic anchor
  under the initial no-offline-progress rule.

M2 includes fake-clock tests for earlier-deadline re-arming, stale wake-ups,
admission pause, full pause/resume, restart anchoring, and a wake that arrives
while a bounded catch-up batch is committing.

This is a deliberate vertical-slice limit. `samhttp` v1 handlers are
synchronous; pretending to have an asynchronous actor bridge would either block
that thread on a condition variable or require new transport capabilities. If
real load demands it, add asynchronous handler support or a transport/runtime
bridge, then run one world actor per thread. The core and command semantics do
not change.

Do not let a timer thread and HTTP handler mutate the world under ad-hoc locks.
A mutex can temporarily protect a prototype, but the desired design has one
explicit owner and ordered admission records.

### Scaling path

1. One process, one world, synchronous small-scale adapter.
2. One process, independent world runtimes; no shared mutable world state.
3. One world-owner process per active world; HTTP routing by world ID.
4. Read replicas/projections for expensive views.
5. Region sharding only after a demonstrated need. Cross-region expeditions and
   ecology make it a distributed transaction problem, not a free optimisation.

## 6. Command transaction and crash boundary

The runtime may stage mutations in scratch memory, but the following must be one
durable unit:

- accepted command and original result (for retries);
- new world revision/sequence;
- authoritative domain effects;
- agenda changes;
- projection updates or an outbox that can reproducibly update them;
- snapshot position when a snapshot is included.

The server acknowledges success only after commit. If persistence fails after an
in-memory mutation, stop admitting work for that world and reload the last
durable state; do not continue with a possibly divergent in-memory world. If the
commit outcome is unknown, first reconnect and reconcile the reloaded world
sequence/command row as specified in the persistence plan; never blindly retry
either a client command or timer mutation. Core invariant failures use the same
closed-admissions recovery path. This is simpler and safer than trying to
hand-roll inverse mutations for every command.

On startup, restore the latest snapshot and apply subsequent recorded effects
without sending responses or repeating external side effects. Deterministic
command re-execution is a separate verifier, not the normal recovery path.

## 7. Observations and information boundaries

There should be one structured observation path with explicit audiences:

- world/public facts;
- household-owned state;
- character/private state;
- administrator/debug views, separately authorised and never sent to ordinary
  bots.

A household observation must not serialize the canonical world and then rely on
the browser to hide fields. Filter before serialization, following the
`samgrognard` side-view principle.

Each response carries `world_id`, `world_revision`, `logical_time`,
`ruleset_version`, and `observation_version`. Event cursors are monotonically
increasing world sequence values encoded as strings in JSON.

## 8. Memory and failure policy

- One long-lived arena owns a loaded world and immutable rules.
- Separate scratch arenas serve command parsing, observation serialization, and
  individual tests.
- A replay/training world has its own arena and can be destroyed as one unit.
- No `malloc`/`free` in library code; follow Ptah's samrena rule.
- Bounds-check all queues, counts, strings, and JSON sizes before arena
  allocation.
- Define overflow checks for time addition, XP, stack quantities, and Favor
  costs.
- Core functions return structured errors; malformed external data never becomes
  a crash or partial command.

## 9. Testing strategy by layer

| Layer         | Tests                                                                                                      |
| ------------- | ---------------------------------------------------------------------------------------------------------- |
| Core unit     | definitions, transitions, costs, XP, inventory, pathfinding, combat equations                              |
| Scheduler     | same-time ordering, cancellation tombstones, stale handles, catch-up bounds, no empty-tick iteration       |
| Golden/replay | fixed seed + command journal = same event bytes/state hash; snapshot includes agenda and counters          |
| Property/soak | many seeds, random legal command sequences, no negative stock, no duplicate IDs, no orphaned agenda owners |
| Protocol      | real socket tests for every route, malformed JSON, auth, idempotency, revision conflicts                   |
| Web           | server-rendered fallback plus small browser tests for tabs, queues, and event refresh                      |
| Recovery      | kill before/after commit, restore and continue without duplicated output or response                       |
| External bot  | bot links no engine and completes the slice through the same API as the CLI                                |

## 10. Architecture acceptance criteria

The architecture is doing its job when:

1. the core test binary runs without sockets, PostgreSQL, or a real clock;
2. a CLI and bot perform all slice actions without linking `samwilds`;
3. the same command stream yields the same state hash under different
   client/request timing when logical admission records are held fixed;
4. cancelling an action cannot later complete its stale agenda entry;
5. a client retry cannot duplicate a shipment, recruit, or item output;
6. an unauthorised household cannot infer or mutate another household's private
   entities;
7. process recovery preserves the world and a season reset cannot receive
   old-world commands;
8. adding a recipe, item, tech node, or ordinary monster is primarily content
   plus tests, not scheduler surgery.
