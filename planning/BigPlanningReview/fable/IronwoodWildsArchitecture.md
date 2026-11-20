# Ironwood Wilds — Server Architecture

**Status:** proposal v0.1 · 2026-11-20 · author: Fable
**Scope:** how the system is cut into modules, and the patterns that keep it
extensible — shown in C, with the object-oriented names you already know
beside them.
**Feeds ticket:** `9e03b041` (samworld architecture doc + dot maps).
**Maps:** `maps/samworld-system.dot` and `maps/samworld-command-flow.dot`,
both in your datasheet style.

> Engine vocabulary is theme-independent, as your `tech-samworld` epic asks:
> **Family** (a House), **Actor** (a settler), **Patron** (the homeland).
> Code sketches show shape; signatures get settled in the TRD.

---

## 1. Summary

One deterministic library, `samworld`, is the whole game. It does no I/O,
reads no clock, and knows nothing about Ironwood. Three programs drive it:

| Driver | Clock | Players | Purpose |
|---|---|---|---|
| **`samworld-server`** | wall clock | humans and bots over the network | the live game |
| **`samworld-lab`** | virtual, as fast as the CPU goes | in-process policies | balance, experiments, breeding bots |
| **replay** (a lab mode) | the journal's timestamps | nobody | verification, forks, footage |

Only three things cross the engine's boundary: **commands in, events out,
views on request**. The theme arrives as a **content pack** (`samironwood`)
that fills in tables. Every feature is either a table row or a handler
registered by a module, so adding content never edits the core.

```
      ┌───────────── drivers: own the clock and every byte of I/O ─────────────┐
      │  samworld-server        samworld-lab            replay                 │
      │  wall clock, network    virtual time, policies  journal timestamps     │
      └─────────┬───────────────────────┬──────────────────────┬───────────────┘
                │      commands in · events out · views on request
      ┌─────────▼───────────────────────▼──────────────────────▼───────────────┐
      │  samworld — the engine          pure: no I/O, clock, floats or globals │
      │  families · actors · actions · commands · events · views · modules     │
      └───────┬──────────────────────────────────────┬─────────────────────────┘
              │ built on                             │ configured by
      ┌───────▼──────────────────┐         ┌─────────▼─────────────────────────┐
      │ samtime                  │         │ samironwood — the content pack    │
      │ clock · due-queue · flows│         │ defs · text · worldgen            │
      └──────────────────────────┘         │          └── samprocgen           │
                                           └───────────────────────────────────┘
        samrena · samdata · samstring                    (foundation, exists)
```

## 2. The laws

Seven rules. Everything else in this document follows from them.

| # | Law | What it buys |
|---|---|---|
| L1 | **One core, three drivers.** Server, lab and replay link the same engine | Tests without sockets; 10,000× speed for the lab; byte-exact replay |
| L2 | **The engine is pure.** No I/O, no wall clock, no floats, no globals, no `malloc` | Determinism is a property of the build, not a hope |
| L3 | **Theme is data.** The engine says Family/Actor/Site; the pack says House/settler/woods | Your theme fork stays cheap; so do experiments |
| L4 | **Commands in, events out, views on request.** Nothing else crosses the boundary | One seam to journal, one to fog, one to push |
| L5 | **Time is lazy.** Nothing runs until something is due *and* somebody touches the world | No tick loop — see `IronwoodWildsCommandsAndTime.md` |
| L6 | **Everything is a row or a registered handler** | Adding content never edits the core |
| L7 | **Ids, never pointers, in world state** | Snapshot, hash, clone and restore are mechanical |

## 3. Inventory: what Ptah already has, and the gaps

The table your architecture ticket asks for.

| Box | Filled today by | Gap |
|---|---|---|
| Arenas | `samrena` (stable) | none |
| Maps, sets, hashes, RNG | `samdata` (stable) | a **stateless keyed RNG**; SHA-256 (lift from `samgrognard_hash.c`) |
| Strings, templates | `samstring` (v1) | none for the slice |
| Async loop, timers, workers | `samplex` — `SAMPLEX_OP_TIMEOUT` and `samplex_dispatch` exist | none |
| HTTP/1.1 | `samhttp` (v1) | handlers are synchronous; `Connection: close` only; no streaming, no TLS. **Enough for REST.** Push needs a *detached response* |
| HTML | `samhtml` (v1) | none — public pages only |
| Static files | `samstatic` | none — or let the edge proxy serve them |
| JSON | `cjson` (external); samgrognard's private codec | no house JSON library. Use `cjson` at the edge; keep it out of the engine |
| Postgres | four per-app `libpq` adapters | no shared helper (optional `sampg` later) |
| **Lazy time engine** | — | **`samtime`** (new) |
| **World engine** | `samvillage`: single-tenant, tick-driven, fixed sites | **`samworld`** (new) — harvest its patterns (§12) |
| **Procedural generation** | `planning/procgen_mvp.md`, `samtemplate` | **`samprocgen`** (new) |
| **Theme content** | — | **`samironwood`** (new) |
| **Journal + snapshots** | samgrognard records (private), samvillage JSON saves | **`samjournal`** (new, generic) |
| Accounts | — | a module in the server app first |
| WebSocket | — | defer; SSE first; `samws` only if it earns it |
| TLS | — | never build it — terminate at Caddy |
| Bots | `samvillage-bot`; samgrognard's versioned policy registry | policies written once, hosted in-process or over HTTP |
| Genetic programs | `samvm` (real), `samgenai` (genetics stubbed) | an environment adapter in the lab, after the slice |
| Deployment | `tools/web/ptah-web`, the systemd unit | one stanza in `servers.conf` |

## 4. Module map

### 4.1 Libraries

| Library | New | Depends on | Owns | Lands |
|---|---|---|---|---|
| `samtime` | yes, generic | samrena, samdata | `SamTime`, wall↔world clock, the due-queue, lazy flows, calendar | day one |
| `samworld` | yes, engine | samtime, samdata, samstring | world state, commands, action driver, events, views, modules, snapshot + hash | day one |
| `samprocgen` | yes, generic | samdata, samstring | fields, sampling, weighted tables, name grammars, text composition | day one, small |
| `samironwood` | yes, pack | samworld, samprocgen | every def, every string, the land generator | day one |
| `samjournal` | yes, generic | samrena, samdata | record envelope, hash chain, snapshot container, file sink, verify | with the server |
| `samdata` *(additions)* | — | — | `samrng_keyed`, SHA-256 | day one |
| `samws`, `sampg`, `samjson`, `samauth` | maybe | — | see §3 | only when a second consumer or a real need appears |

Rule for the "maybe" row, straight from AGENTS.md: a module is born inside
the app that needs it and graduates to `libs/` when a second consumer is
real.

### 4.2 Applications

| App | Links | Does not link | Purpose |
|---|---|---|---|
| `samworld-lab` | samworld, samironwood, samjournal | samhttp, libpq | `worldgen`, `run`, `sweep`, `replay`, `verify`, `fork`, `bless` — the samgrognard-console of this project |
| `samworld-server` | samworld, samironwood, samjournal, samhttp, samplex, cjson, libpq (gated) | — | the live game: API, accounts, persistence, web assets |
| `samworld-bot` | libcurl, cjson, the shared policy code — which needs only the view *headers* | any engine object code | proves the API is playable from outside the process |

### 4.3 Dependency graph

```
samrena → samdata → samstring
              ├── samtime ─────────→ samworld ─────────→ samironwood
              ├── samprocgen ──────────────────────────────┘
              └── samjournal

apps/samworld-lab     → samworld, samironwood, samjournal
apps/samworld-server  → samworld, samironwood, samjournal, samhttp, samplex, cjson, libpq
apps/samworld-bot     → libcurl, cjson            (no engine link — the API is the test)
```

The engine cannot reach the network or the database because it does not link
them. That is the architecture's main enforcement mechanism, and it is free.

## 5. Rosetta: your hexagonal vocabulary, in C

You laid Biofabrica out as bounded contexts with `domain / ports /
application / adapters` and ArchUnit keeping the arrows honest. The same
design, translated:

| You know it as | Here it is | Kept honest by |
|---|---|---|
| Bounded context (a Gradle module) | a library under `libs/` | CMake `DEPENDENCIES` — unlinked means unincludable |
| `domain` | `libs/samworld/src/` | the architecture test (§9) |
| Inbound port / use case | functions in `samworld.h`: `samworld_submit`, `samworld_view_family`, `samworld_advance_to` | opaque handle; no struct bodies in public headers |
| DTO | view structs (`SamWorldFamilyView`) | views are copies, never pointers into world state |
| Outbound port (SPI) | a struct of function pointers plus `void *impl` | house pattern — `samtasker`'s repository, samtechtree's `st_reader.h` |
| Adapter | `apps/samworld-server/src/adapters/*.c` | only those files may include `libpq-fe.h` or `cJSON.h` |
| Application service | the driver: the server's `sim.c`, the lab's `run.c` | — |
| Composition root / DI container | `main.c`, wiring by hand | — |
| Interface + implementations | a vtable struct (`SamWorldMechanic`) | — |
| Enum-with-fields / flyweight | a def table row | — |
| `ServiceLoader` plugin | a `SamWorldModule` in one static array | — |
| Domain event | `SamWorldEvent` | — |
| ArchUnit | `samworld_arch_test` under CTest | CI |

## 6. The patterns, shown

### 6.1 Type Object (Prototype, Flyweight): definitions and instances

The oldest trick in data-driven games, and the one that makes "theme is
data" real. A **definition** is immutable and shared; an **instance** is a
def id plus the little that varies.

```c
/* A definition: one row, immutable, comes from the pack. */
typedef struct SamWorldItemDef {
  const char *key;           /* "iron_ore" — stable wire name            */
  const char *name;          /* "iron ore" — display text                */
  uint16_t    weight;        /* carry units                              */
  uint16_t    tool_class;    /* 0 = not a tool                           */
  uint16_t    tool_permille; /* 750 = work takes 75 % as long            */
} SamWorldItemDef;

/* An instance: tiny, mutable, lives in the world. */
typedef struct SamWorldStack {
  SamWorldItemId item;       /* index into rules->items                  */
  uint32_t       qty;
} SamWorldStack;
```

In Java this is an enum with fields, or a flyweight. *Prototype* shows up
when a Family is founded or a monster spawns: copy the def's starting values
into a fresh instance.

**The ruleset is an object, not a global.** A pack is a function that fills
a builder; the builder resolves cross-references, validates, and produces an
immutable `SamWorldRules` with a digest.

```c
typedef struct SamWorldPack {
  const char *id;                              /* "ironwood"                    */
  uint32_t    version;
  void (*define)(SamWorldRulesBuilder *b);     /* items, skills, actions, text… */
  const SamWorldGenOps *worldgen;              /* how this theme makes land     */
} SamWorldPack;

/* libs/samironwood/src/actions.c — content reads like the spec table.
 * A row names other things by key. */
static const SamWorldActionRow ACTIONS[] = {
  {.key = "chop",  .name = "Fell timber", .mechanic = "gather",
   .skill = "woodcutting", .tool = "axe", .base_ms = 20000, .xp = 25},

  {.key = "smelt", .name = "Smelt iron",  .mechanic = "craft",
   .skill = "smithing", .station = "bloomery", .base_ms = 90000, .xp = 60,
   .inputs  = {{"iron_ore", 2}, {"charcoal", 1}},
   .outputs = {{"iron_ingot", 1}},
   .needs   = {{SAMWORLD_NEED_SKILL, "smithing", 5}}},
};

SamWorldRulesBuilder *b = samworld_rules_begin(arena);
samironwood_pack()->define(b);                              /* the theme      */
samworld_rules_override(b, "action.smelt.base_ms", "45000"); /* an experiment  */
const SamWorldRules *rules = samworld_rules_finish(b, &err); /* validated, hashed */
```

`finish` turns rows into defs: every key becomes an id or a pointer — the
`SamWorldActionDef` the driver in §6.4 reads — and a pack with a dangling
reference is refused there, not discovered in play.

Three things fall out of this for free:

- **Late binding.** Swap the pack, get the space game.
- **The Lab's knob.** `--set action.smelt.base_ms=45000` is one override; no
  recompile. This is the "tweak a population and test a hypothesis" tool.
- **A rules digest.** A hash of the built ruleset goes in the journal header,
  so a replay refuses to run under different numbers.

The house rule stands: the tables are mirrored from a `spec/` folder, and a
change is spec → table → version bump → re-bless.

### 6.2 Plugin (SPI): modules

Each system is a module. The core knows none of them by name.

```c
typedef struct SamWorldModule {
  const char *name;                      /* "gather"                             */
  uint32_t    version;                   /* bumps when its snapshot shape changes */
  void (*install)(SamWorldRegistry *r);  /* mechanics, commands, timers, events  */
  bool (*world_init)(SamWorld *w);       /* allocate this module's pools         */
  void (*snapshot_write)(const SamWorld *w, SamSnapWriter *out);
  bool (*snapshot_read)(SamWorld *w, SamSnapReader *in, uint32_t version);
} SamWorldModule;

/* src/modules.c — the only list. Order is dispatch order, so it is part of
 * determinism and is never sorted or discovered at run time. */
static const SamWorldModule *const MODULES[] = {
  &samworld_mod_core,   &samworld_mod_travel, &samworld_mod_gather,
  &samworld_mod_craft,  &samworld_mod_patron,
  /* later: build, tech, combat, raid */
};
```

| Module | Owns | Mechanics | Commands | Slice |
|---|---|---|---|---|
| `core` | families, actors, inventories, skills, queues, calendar | `wait`, `transfer` | `family.found`, `queue.append`, `queue.replace`, `queue.clear`, `action.cancel` | yes |
| `travel` | site state, discovery | `travel`, `explore` | — | yes |
| `gather` | resource nodes | `gather` | — | yes |
| `craft` | station slots | `craft` | — | yes |
| `patron` | Favor, demand, war effort | `ship` | `patron.recruit` | yes |
| `build` | structures, construction | `build`, `repair` | `structure.plan` | slice 1 |
| `tech` | research progress, unlocks | `study` | `tech.begin` | slice 1 |
| `combat` | parties, encounters, lairs | `engage` | `party.form`, `party.tactics`, `party.retreat` | slice 2 |
| `raid` | threat, defence plans | — | `defence.set` | slice 4 |

Modules never call each other. They talk through the core's services
(inventory, requirements, the due-queue) and through events (§6.5).

### 6.3 Command: one struct, one table

A command is data. A handler is a row.

```c
typedef struct SamWorldCommandDef {
  const char *op;                      /* "queue.append"                      */
  const SamWorldArgSpec *args;         /* drives decode, validation, the      */
  uint32_t arg_count;                  /*   published catalog, the web prompt */
  SamWorldError (*check)(const SamWorld *w, const SamWorldCommand *c,
                         SamWorldReply *preview);     /* pure: const world    */
  void (*apply)(SamWorld *w, const SamWorldCommand *c,
                SamWorldReply *out);                  /* cannot fail          */
} SamWorldCommandDef;

SamWorldError samworld_submit(SamWorld *w, const SamWorldCommand *c,
                              SamWorldReply *out) {
  const SamWorldCommandDef *def = samworld_command_def(w, c->op);
  if (!def) return SAMWORLD_ERROR_UNKNOWN_OP;

  SamWorldError err = samworld_authorize(w, c);   /* is that Actor yours? alive? */
  if (err == SAMWORLD_ERROR_NONE) err = def->check(w, c, out);
  if (err != SAMWORLD_ERROR_NONE) return err;     /* world untouched            */

  def->apply(w, c, out);
  w->command_seq++;
  return SAMWORLD_ERROR_NONE;
}
```

Two properties do a lot of work:

- **`check` takes a `const` world.** The compiler enforces that validation
  cannot mutate, which is what makes `dry_run` free and rejection safe.
- **`apply` cannot fail.** Everything that could go wrong was checked. So a
  command either happens completely or not at all, and the journal holds
  only commands that happened.

Because the arg spec drives decoding, a module adds a command without
touching the transport, the docs, or the web client.

### 6.4 Strategy and Template Method: mechanics

An **action** (a table row) names a **mechanic** (code). Ten mechanics can
carry three hundred actions.

```c
typedef struct SamWorldMechanic {
  const char *key;    /* "gather" */
  SamWorldError (*check_enqueue)(const SamWorld *, const SamWorldActor *,
                                 const SamWorldActionReq *);
  SamWorldError (*check_start)(const SamWorld *, const SamWorldActor *,
                               const SamWorldAction *);
  SamTime  (*duration)(const SamWorld *, const SamWorldActor *,
                       const SamWorldAction *);
  void     (*on_start)(SamWorld *, SamWorldActor *, SamWorldAction *);
  void     (*on_complete)(SamWorld *, SamWorldActor *, SamWorldAction *);
  void     (*on_cancel)(SamWorld *, SamWorldActor *, SamWorldAction *);
  uint32_t (*enumerate)(const SamWorld *, const SamWorldActor *,
                        SamWorldOption *out, uint32_t max);  /* "what can I do here?" */
} SamWorldMechanic;
```

The *template method* is the driver in the core. It owns the order of
operations; mechanics fill in the steps.

```c
static void actor_start_next(SamWorld *w, SamWorldActor *a) {
  while (a->queue_len > 0) {
    SamWorldAction *act = &a->queue[0];
    const SamWorldActionDef *def = &w->rules->actions[act->def];
    const SamWorldMechanic  *m   = def->mechanic;

    SamWorldError err = samworld_needs_met(w, a, def);   /* skill, tech, tool gates */
    if (err == SAMWORLD_ERROR_NONE) err = m->check_start(w, a, act);
    if (err != SAMWORLD_ERROR_NONE) {                    /* fail-soft: note it, move on */
      samworld_emit_action_failed(w, a, act, err);
      actor_queue_pop(a);
      continue;
    }
    m->on_start(w, a, act);                              /* take inputs, take a slot */
    a->ends_at = w->now + m->duration(w, a, act);
    a->timer   = samdue_push(&w->due, a->ends_at, SAMWORLD_DUE_ACTION_DONE, a->id);
    a->state   = SAMWORLD_ACTOR_BUSY;
    return;
  }
  if (a->state != SAMWORLD_ACTOR_IDLE) {
    a->state = SAMWORLD_ACTOR_IDLE;
    samworld_emit_actor_idle(w, a);
  }
}
```

Compare `samvillage_action.c`: five `switch` statements over the action
type, and a new action can touch each of them. That was right for eighteen
actions. It is the wrong shape for a tech tree.

Gates are **data, not code**: `needs = {{SKILL, "smithing", 5}, {TECH,
"bloomery"}}` is checked by one function for every def. A new requirement
kind is one `case`, and every action can use it.

### 6.5 Observer: the event bus

```c
typedef void (*SamWorldEventFn)(SamWorld *w, const SamWorldEvent *ev, void *user);

void samworld_subscribe(SamWorldRegistry *r, SamWorldEventType type,
                        SamWorldEventFn fn, void *user);
void samworld_emit(SamWorld *w, const SamWorldEvent *ev);
```

- Subscribers run in registration order — deterministic.
- `emit` queues; the queue drains after the current handler returns. No
  re-entrancy surprises.
- An event is a small fixed struct of ids and integers. **No strings.** Text
  is rendered later from the pack's template for that event type.
- Every event carries an **audience**: public, or one Family. The feed a
  client reads is filtered by it.

The player-facing log, skill XP, goals, the Lab's statistics and (later) the
tech module's unlock checks are all just subscribers. Adding a consequence
to "an action finished" never touches the code that finishes actions.

### 6.6 The entity schema: tables of plain structs, not an ECS

You asked to see this choice. Here are both shapes.

**An ECS**: identity is a bare id; data lives in per-component columns;
behaviour is a loop over a column.

```c
typedef uint32_t Entity;
Inventory *inv = column_get(&w->inventory, e);    /* e may or may not have one    */
for (uint32_t i = 0; i < w->health.n; i++)        /* a "system" = a loop over a   */
  regenerate(&w->health.data[i]);                 /*   column, every frame        */
```

**What I recommend**: one typed pool per kind of thing, with the few shared
capabilities as embedded structs.

```c
typedef struct SamWorldInventory {                /* a "component", by embedding  */
  uint16_t cap, used, n;
  SamWorldStack stacks[SAMWORLD_INV_SLOTS];
} SamWorldInventory;

typedef struct SamWorldActor {
  SamWorldActorId   id;        /* == its index; never reused within a world */
  SamWorldFamilyId  family;
  SamWorldSiteId    site;
  uint8_t           state;     /* IDLE, BUSY, DEAD                          */
  uint32_t          xp[SAMWORLD_SKILL_MAX];
  SamWorldInventory pack;      /* the same struct a Structure embeds        */
  SamWorldAction    running;   SamTime ends_at;   SamDueId timer;
  SamWorldAction    queue[SAMWORLD_QUEUE_MAX];    uint8_t queue_len;
  uint32_t          rolls;     /* this Actor's keyed-RNG counter            */
} SamWorldActor;

struct SamWorld {
  const SamWorldRules *rules;
  SamTime      now;
  SamDueQueue  due;
  uint64_t     command_seq, event_seq;
  SamWorldFamilyVec    families;      /* append-only; id == index */
  SamWorldActorVec     actors;
  SamWorldStructureVec structures;
  SamWorldSiteStateMap sites;         /* only sites something has happened to */
  void *module_state[SAMWORLD_MODULE_MAX];
  SamWorldEventRing    events;
};
```

| Question | ECS | Typed pools (recommended) |
|---|---|---|
| What is it good at? | Thousands of entities, each touched every frame | A few kinds, each touched *when its timer fires* |
| How does work get triggered here? | Systems scanning columns | A due event names one Actor |
| Kinds of thing | Open-ended, mixed traits | About ten, well known |
| Reading the code | "What is entity 4107?" needs a tool | `w->actors.items[104]` in a debugger |
| Persistence | Per-column serialisers | One struct, one section; mirrors the tables a Java dev would draw |
| Cost of being wrong | — | Low: move a shared struct into a column later |

The decisive row is the second. A lazy, event-driven simulation has no
per-frame systems, which is the thing an ECS exists to make fast.

**Ids, not pointers (L7).** Pools are append-only for the life of a world,
and a dead Actor keeps its slot — the samvillage rule, "retained for the
log, never reused". Seasons bound the growth. Because nothing in world state
is a pointer, a snapshot is a walk over arrays, a hash is a hash of that
walk, and a clone is a snapshot read back into another arena.

### 6.7 Facade and DTO: views, fogged by construction

There is exactly one way to learn about the world, and it takes a Family.

```c
bool samworld_view_family(const SamWorld *w, SamWorldFamilyId who,
                          Samrena *scratch, SamWorldFamilyView *out);
bool samworld_view_site(const SamWorld *w, SamWorldFamilyId who,
                        SamWorldSiteId site, Samrena *scratch,
                        SamWorldSiteView *out);
uint32_t samworld_options(const SamWorld *w, SamWorldActorId actor,
                          SamWorldOption *out, uint32_t max);
```

The website, the HTTP bot, the lab's policies and a future genome all
receive the same structs. This is samgrognard's principle — *blindness is
enforced by construction* — and it means a bot can never see more than a
person, because there is no other getter to call. The one exception is an
explicit omniscient view for the spectator page and the lab's metrics, kept
in a separate header.

### 6.8 Ports and adapters: where I/O lives

The engine has no ports; it has nothing to plug into. Ports belong to the
drivers.

```c
/* A port: a struct of function pointers and a cookie. */
typedef struct SamJournalSink {
  void *impl;
  bool (*append)(void *impl, const SamJournalRecord *rec); /* must not block */
  bool (*flush)(void *impl);
  void (*close)(void *impl);
} SamJournalSink;

/* apps/samworld-server/src/main.c — the composition root. */
SamJournalSink  journal  = cfg.db ? pg_journal_open(arena, cfg.db)
                                  : file_journal_open(arena, cfg.dir);
SnapshotStore   snaps    = cfg.db ? pg_snapshots_open(arena, cfg.db)
                                  : file_snapshots_open(arena, cfg.dir);
AccountStore    accounts = cfg.db ? pg_accounts_open(arena, cfg.db)
                                  : file_accounts_open(arena, cfg.dir);
Sim *sim = sim_create(arena, world, clock, journal, snaps);
api_register(http, sim, accounts);
```

Two adapters per port from the start — files and Postgres — is not
speculation. It is the samtasker pattern, and the file adapter is what keeps
the tests hermetic and lets the slice run before a database exists.

### 6.9 Memento and event sourcing

A **snapshot** is a memento: the world, serialised by section, one section
per module. The **journal** is the list of accepted commands with their
world timestamps. Together:

```
state(now)  =  load(latest snapshot)  +  replay(journal after it)
```

Detail in `IronwoodWildsAccountsAndStorage.md`. The architectural point is
that this needs nothing from the engine beyond L2 and L7.

### 6.10 The rest, briefly

| Pattern | Where it appears |
|---|---|
| State | An Actor's `IDLE / BUSY / DEAD`, with the transitions in one file |
| Factory | `samworld_spawn_actor(w, family, def)` and friends |
| Mediator | The world and its due-queue; modules never call each other |
| Chain of Responsibility | The validation layers a command passes through |
| Interpreter | Requirements and effects as small data lists; later, `samvm` genomes |
| Singleton | None. No globals — house principle |

## 7. The live server: one process, two threads

```
  clients ──HTTPS──▶ Caddy ──HTTP/1.1, loopback──▶ samworld-server
                     (TLS, static files, limits)

  ┌───────────────────────────── samworld-server ─────────────────────────────┐
  │                                                                           │
  │  ENGINE THREAD (the samplex loop) — the only thread that touches the world│
  │                                                                           │
  │   samhttp ──▶ transport ──▶ sim ──────────────▶ samworld                  │
  │   parse       token→family  advance_to(now)     check · apply · view      │
  │   route       rate limit    submit / view                                 │
  │               decode JSON   encode reply                                  │
  │                               │                                           │
  │   housekeeping timer (1 Hz) ──┘  advance, time marks, snapshot schedule   │
  │                               │                                           │
  │                               ▼  immutable buffers, one queue             │
  │  WRITER THREAD ───────────────────────────────────────────────────────    │
  │   journal batches · snapshots · stats rows ──▶ Postgres or files          │
  └───────────────────────────────────────────────────────────────────────────┘
```

**Single writer.** samhttp handlers already run synchronously on the engine
thread, so the world needs no lock at all — unlike `apps/samvillage`, which
shares a mutex between the HTTP thread and a clock thread. With lazy time
there is no clock thread to share with.

**Nothing slow on the engine thread.** The writer thread receives finished
byte buffers and never sees the world. Token checks hit an in-memory map.
The only blocking database calls on the engine thread are at start-up and
at registration, which is rare; when samhttp gains detached responses those
move to `samplex_dispatch`.

**Is one thread enough?** Yes, by a wide margin. A due event costs well
under a microsecond; a command, a few. The expensive part of a request is
HTTP and JSON. Numbers are in `IronwoodWildsCommandsAndTime.md` §6.6.

**When storage falls behind.** The queue to the writer is bounded. Past a
threshold the server goes read-only — commands answer `503
STORAGE_UNAVAILABLE`, views keep working — so the durable record never lags
the live world by more than a known amount.

**One world, one process.** A second world is a second process on another
port. Nothing in the design needs more.

## 8. Extensibility: what each kind of change costs

The test of the patterns above.

| You want to add… | You touch | You do not touch |
|---|---|---|
| A resource and a way to gather it | pack: an item row, a node-kind row, an action row | engine, server, web client |
| A recipe | pack: one action row | — |
| A skill | pack: one skill row | — |
| A requirement on anything | pack: one `needs` entry | — |
| New behaviour (fishing with bite times) | engine: `src/modules/fish.c` — one mechanic — plus a line in `modules.c`; pack rows | core, server, web client |
| A command | the owning module: one def and its arg spec | transport, docs, web client |
| A whole system (combat) | a new module: pools, mechanics, commands, timers, events, a snapshot section | core, other modules |
| A transport (SSE, WebSocket, a line protocol) | server: one adapter | engine |
| A storage backend | server: one adapter per port | engine |
| A theme (the space game) | a new pack library | engine, server |
| An experiment | `--set action.smelt.base_ms=45000` | any source file |

The web client survives all of these because it renders what the server
describes — the command catalog, each Actor's options, each event's text —
and computes no rules of its own.

## 9. Architecture tests

The ArchUnit of this build: a script under CTest, `samworld_arch_test`,
that fails on the first violation.

| Rule | How it is checked |
|---|---|
| The engine is pure | In `libs/samworld/src` and `libs/samtime/src`, matched on word boundaries: no `time`, `clock_gettime`, `gettimeofday`; no `fopen`, `socket`, `printf`, `fprintf`; no `rand`, `random`; no `malloc`, `calloc`, `realloc`, `free`; no `float` or `double` |
| Modules are strangers | A file under `src/modules/` includes only `core/*.h` and public headers |
| The pack sees only the public API | `libs/samironwood` never includes a path under `samworld/src` |
| I/O stays in adapters | Only `apps/samworld-server/src/adapters/` includes `libpq-fe.h` or `cJSON.h` |
| World state is opaque | No struct body for world state in `libs/samworld/include` |
| `check` cannot mutate | Free — it takes `const SamWorld *` |

The first row is samgrognard's principle that determinism is a type
property, and its integer-only decision (D9), extended to clocks and I/O.

## 10. Layout

```
libs/samtime/
  include/samtime.h   src/{clock,due,flow,calendar}.c   test/

libs/samworld/
  include/samworld.h
  include/samworld/{types,rules,world,command,event,view,module}.h
  spec/                    # mechanics contracts; the numbers live in the pack
  src/core/{world,rules,registry,command,queue,inventory,skill,event,view,
            snapshot,hash}.c
  src/modules/{travel,gather,craft,patron}.c      # later: build, tech, combat, raid
  src/modules.c            # the one list
  test/                    # unit, golden, schedule-independence, arch

libs/samprocgen/
  include/samprocgen.h   src/{field,sample,table,name,text}.c   test/

libs/samironwood/
  include/samironwood.h
  spec/                    # the content source of truth, one file per topic
  src/{items,skills,actions,sites,biomes,names,text,events,start,worldgen}.c
  test/                    # golden numbers, worldgen gates

libs/samjournal/
  include/samjournal.h   src/{record,chain,snap,file_sink,verify}.c   test/

apps/samworld-lab/      src/{main,worldgen,run,sweep,replay,fork,bless}.c
                        src/policies/{greedy_v0}.c
apps/samworld-server/   src/main.c
                        src/{sim,api,wire,auth,ratelimit}.c
                        src/ports/*.h   src/adapters/{file_*,pg_*}.c
                        migrations/   web/   server.conf   run.sh
apps/samworld-bot/      src/{main,http_host}.c
```

`src/core/` and `src/modules/` are the one departure from "a flat `src/`";
samgrognard's `src/bots/` is the precedent.

## 11. Key decisions

| # | Decision | Rejected | Why |
|---|---|---|---|
| D1 | One deterministic engine library; server, lab and replay drive it | Game logic inside the server | Lab speed, socket-free tests, replay. samvillage and samgrognard both did this |
| D2 | Single writer: every mutation on one thread | Locks, per-Family threads | Determinism and simplicity; the load is tiny |
| D3 | World in memory; durability is a command journal plus snapshots | Postgres rows as the live state | Purity, replay, forks, the lab. SQL keeps identity and read models |
| D4 | Lazy time: a due-queue and flows, advanced on touch | A tick loop; resolve-on-observe per entity | `IronwoodWildsCommandsAndTime.md` §6 |
| D5 | Theme-independent engine, theme as a pack | Ironwood names in the engine | Your epic. Free with type objects |
| D6 | Actions are rows; mechanics are registered strategies | An enum and switches | A tech tree means hundreds of actions |
| D7 | Typed pools with stable ids | A full ECS | No per-frame systems to speed up; pools mirror persistence |
| D8 | Integer-only engine; stateless keyed randomness | Floats; one global RNG stream | Bit-exact replay; clean A/B experiments |
| D9 | The ruleset is an explicit object built per world | Global const tables | Overrides, theme swap, digest |
| D10 | One command endpoint, decoding driven by arg specs | A route per verb | Modules add commands without touching transport or client |
| D11 | REST and a cursor first; SSE second; WebSocket last | WebSocket first | It is what samhttp does today, and actions take minutes |
| D12 | File adapters first, Postgres when going online | Postgres from day one | Hermetic tests; the slice runs anywhere |
| D13 | A new engine, not samvillage v2 | Growing samvillage | Different time model, tenancy and world. Its patterns carry over; its code mostly does not |

## 12. What to harvest from code you already have

| From | Take | Leave |
|---|---|---|
| `samvillage` | Two-phase validation and the fail-soft queue; `GET /v1/rules`; the cursor event feed; the error envelope; the bot as acceptance instrument and the frozen-policy test; the `spec/` + `--bless` discipline | The 1 Hz tick loop; the single world struct of fixed arrays; cjson inside the engine; the action switch |
| `samgrognard` | One fogging path for every surface; the integer law; records as part of the simulation; replay, verify and fork; the versioned policy registry; the console as measurement instrument | Zero randomness (this game wants dice); JSON as the state format |
| `samtasker`, `samtechtree` | Ports as structs of function pointers; one schema per app; embedded numbered migrations and `--migrate`; the by-hand role file | — |
| `samgenai-studio` | The run-state and worker-thread shape for a future training UI | — |

On samvillage v2: its depth mechanics — spoilage, tools, soil, seasons — are
exactly the kind of content this engine is meant to hold. I would fold them
into this roadmap as pack content and mechanics rather than build v2 on the
old engine. Your call; nothing here depends on it.

## 13. Risks and honest caveats

| Risk | Mitigation |
|---|---|
| **Scope.** Five new libraries is a lot for Fridays | They are small, and the slice needs only thin versions. `IronwoodWildsPrototypeSlice.md` orders them so something runs at every step |
| **A hand-rolled C server on the public internet** | Caddy in front; hard limits; fuzz the parser and the wire decoder; ASan/UBSan runs; the unit's existing systemd hardening; prepared statements only |
| **Determinism rots quietly** | The architecture test, a golden hash, the schedule-independence property test, and hash checkpoints in every journal |
| **Generality tax.** A theme-independent engine can become an engine for no game | The pack is written alongside, from day one. Nothing enters the engine without a row in `samironwood` that uses it |
| **Blocking calls on the engine thread** | Token cache in memory; registration is rare; detached responses move the rest to workers |
| **Single process is a single point of failure** | Journal plus snapshot recovery; a restart costs seconds and loses at most the unflushed batch |
