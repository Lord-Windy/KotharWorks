# Sam Village — Technical Requirements Document

## 1. Overview

### 1.1 Purpose

Sam Village is an **offline, API-driven village management game**: a local
server simulates a small village where every villager's actions — sleeping,
eating, farming, cooking, crafting, building, exploring — are commanded over
a REST API by a client the player controls (a CLI now, a program/bot later).
The server is a pure executor and referee: it advances the world on a real-
time tick, validates commands, applies the rules in this document, and reports
state. All intelligence lives client-side.

This is **phase 1 of 3** for the larger idea:

| Phase | What | Status |
|---|---|---|
| 1 | Offline vertical slice (this TRD) — prove the API-driven loop is fun and workable locally | specified here |
| 2 | Take the same game online: persistent hosted world, multiple players, real bots running 24/7 | out of scope; shaped by §6 |
| 3 | The full vision: Factorio-depth production optimization, PvE, the "real" game | out of scope; shaped by §6 |

The slice deliberately does **not** test whether "REST game as a genre"
works — SpaceTraders and Artifacts MMO proved that. It tests whether *this*
game's loop (needs + timers + short queues + production chains + events +
population growth, all driven through an API) hangs together and is worth
taking online.

Inspirations and what is borrowed from each:

| Inspiration | Borrowed |
|---|---|
| SpaceTraders | REST + bearer token, timed actions, action queues, self-describing API |
| A Tale in the Desert | Hand-scale production chains, no combat, cooperative building |
| Factorio | Chain optimization, maintenance pressure, throughput planning |
| Dwarf Fortress | Village-level needs simulation (hunger, sleep, clothing), families and lineage |
| EVE | Long-horizon planning — consciously **not** borrowed: queue depth is capped small (§4.4) |

**No combat, no enemies, no PvP, no market.** Those are phase 2/3 concerns.

This document is normative for game mechanics (§4), the API (§5), and
architecture (§6). Per the spec-driven rule: every mechanic that ships must
be specified here first; numbers in §4 are tunable constants, but their
*names, locations, and semantics* change only by editing this document.

### 1.2 Position in the Dependency Graph

```
samrena → samdata → samvillage            (new library: simulation engine)
                     └── samstring, cjson

apps/samvillage     → samvillage, samhttp, samplex, samrena, samdata, cjson
apps/samvillage-cli → (libcurl, cjson only — talks HTTP, no engine link)
```

- `libs/samvillage` — the game engine: world state, tick, rules, save/load.
  Headless, deterministic, JSON-aware (cjson) for persistence only.
- `apps/samvillage` — the server binary: samhttp front end, command
  validation, autosave. Thin.
- `apps/samvillage-cli` — the human client binary (`village` command):
  libcurl + cjson, no engine dependency — it knows only the HTTP API, which
  is the point of the slice.
- `apps/samvillage-bot` — the autopilot test binary: same rule as the CLI
  (libcurl + cjson, no engine link). It *plays* the game over HTTP so the
  play pattern itself is under test (§3.7, §9.5).

`samspacetraders` (existing stub) is unrelated plumbing-wise: it is a client
library for the *real* SpaceTraders game. Sam Village is its own engine and
takes no dependency on it.

### 1.3 Design Principles

1. **Engine is pure and deterministic.** `libs/samvillage` performs no I/O
   during simulation; all randomness flows from one seeded `SamRng`; same
   seed + same command sequence at the same ticks → identical world state.
   The HTTP layer is a replaceable adapter.
2. **Spec-as-data.** Crops, actions, recipes, buildings, events, and sites
   are data tables (§4) — compiled into the engine as constant tables, served
   verbatim by `GET /v1/rules`, and versioned together
   (`SAMVILLAGE_RULES_VERSION`). One source of truth, three consumers
   (engine, API, tests).
3. **Villagers are dumb executors.** The server never plans, prefers, or
   auto-recovers for a villager. Passive decay (hunger, wear, building
   condition) is simulation; anything requiring choice is a client command.
4. **Fail-soft actions.** Actions are validated twice — at enqueue (client
   gets an immediate error) and at start (world may have changed). A
   start-time failure logs an event and the queue advances. The bot copes,
   like a SpaceTraders bot copes with a failed jump.
5. **API-first.** Everything a client needs to decide is readable via GET —
   including the rules tables. A client that hardcodes nothing but endpoints
   is the acceptance bar.
6. **Offline means no fairness constraints.** Pause, time scale, and
   save/load exist because there is no other player to cheat. They are
   designed to be removable in phase 2 (§6).
7. **Arena-first, house rules.** Every allocation routes through a
  `Samrena *`; umbrella header `samvillage.h`; Apache-2.0 headers; tests as
  plain C binaries under CTest, per AGENTS.md.

### 1.4 Key Decisions at a Glance

| # | Decision | Where |
|---|---|---|
| D1 | Transport is REST/JSON over HTTP (samhttp), polling only; no websockets in v1 | §3.2 |
| D2 | Engine is a library (`libs/samvillage`), not app-internal | §3.4 |
| D3 | Persistence is versioned JSON snapshots; cjson is an engine dependency | §3.5, §7 |
| D4 | Time: 1 tick = 1 real second; 1 game day = 10 real minutes; pause + time scale | §4.1 |
| D5 | Starvation kills slowly (~2 game days of neglect); no old-age death | §4.3 |
| D6 | Queue cap: 1 running + 4 pending per villager | §4.4 |
| D7 | Action durations include travel; no movement/position model in v1 | §3.6, §4.10 |
| D8 | Exploration is a fixed list of named off-map sites, revealed in order | §4.10 |
| D9 | Rules tables are engine constants + `GET /v1/rules` + test fixtures | §4.0, §5.2 |
| D10 | Mutations that aren't plain REST verbs are `POST /v1/<noun>/<verb>` subpaths (samhttp has no PATCH) | §5.2 |
| D11 | Single bearer token, generated at world creation, stored in the save | §5.1 |
| D12 | Human client is a C CLI (`apps/samvillage-cli`, libcurl); reference bot deferred | §3.3 |
| D13 | Full lineage: sexes, couples, surnames, parents, households, inherited aptitude | §4.9 |
| D14 | Goal board: static milestones served at `GET /v1/goals`, informational only | §4.13 |
| D15 | A C autopilot bot (`apps/samvillage-bot`) plays over HTTP as the living test harness | §3.7, §9.5 |
| D16 | Game content tables are collated in `libs/samvillage/spec/` (one file per topic) — the TRD points, the spec holds the numbers | §4.0, §14 |

## 2. Design Considerations

### 2.1 What the Slice Proves

| Hypothesis | How the slice tests it |
|---|---|
| Commanding individuals (not aggregates) via API is playable | 6 villagers × short queues, all state visible via GET |
| Needs + timers create real scheduling pressure | Sleep/hunger/energy interact with action durations (§4.3-§4.4) |
| Production chains are engaging at hand scale | wheat→flour→bread, flax→cloth→clothes, wood as fuel + material (§4.5-§4.7) |
| Events create planning, not busywork | rot/blight/rains/wanderer with mitigation levers (§4.8) |
| Population growth is a satisfying goal | births gated on beds + food; children mature (§4.9) |
| The API shape survives going online | token, /v1, cursor event feed, engine/lib split (§6) |
| An unattended program can actually run the village | the C autopilot plays over HTTP, unaided (§3.7, §9.5) |

### 2.2 Workload Profile

| Property | Consequence |
|---|---|
| ≤ ~30 villagers, ≤ ~50 buildings, one village | Whole world fits in a single arena; linear scans everywhere are fine |
| 1 tick/second; minutes-scale actions | Tick work is trivial; no fixed-timestep catch-up needed |
| One local client polling ~1 req/s | samhttp `Connection: close` per-connection arenas are adequate; no rate limiting in v1 |
| Bots poll and react | Event feed with a monotonic cursor (§5.2); ETags unnecessary |
| Server may run for days (phase-2 behavior) | Autosave + clean SIGTERM save + load-on-start from day one (§7) |

### 2.3 Honored Deviations from SpaceTraders

Deliberate differences, recorded so they read as design, not drift: queued
actions are capped at 4 pending (SpaceTraders allows hours of flight plans);
the world advances only while the server runs (no catch-up on downtime);
there is one token with full authority (no multi-ship account model).

## 3. Option Analysis

### 3.1 Process Model

| Option | Pros | Cons |
|---|---|---|
| **A. Server binary + separate CLI binary** | Server is the artifact phase 2 needs; CLI proves the API is usable from outside the process; crash/isolation boundaries are real | Two executables to build/run |
| B. Single binary with subcommands (`samvillage serve`, `samvillage queue …`) | One artifact | Blurs the client/server seam the slice exists to prove; phase 2 throws half of it away |
| C. In-process library + no server | Least code | Tests nothing about the API experience; contradicts the purpose |

**Recommended: A.** The seam is the experiment. **Decision:** `apps/samvillage`
(server) + `apps/samvillage-cli` (client).

### 3.2 Transport

| Option | Pros | Cons |
|---|---|---|
| **A. REST/JSON over HTTP (samhttp)** | Phase-2 shape; curl-able for humans; samhttp already routes GET/POST/PUT/DELETE with `:param` patterns and has a JSON response helper; polling suits 1s ticks | Polling latency (irrelevant at this scale) |
| B. Websocket/long-poll push | Lower latency | samhttp has no WS; push complicates clients; phase-2 concern imported early |
| C. Length-prefixed socket protocol | No HTTP overhead | Zero ecosystem leverage; everything (framing, auth, docs) hand-built |

**Recommended: A**, with the event-cursor feed making a later upgrade to push
non-breaking. **Decision:** REST/JSON, polling only.

### 3.3 Client Deliverable

| Option | Pros | Cons |
|---|---|---|
| **A. C CLI (libcurl + cjson)** | Lives in-repo with house stack; libcurl already a repo dependency (samtrader); forces the API to be genuinely usable | C string handling for JSON is chattier than Python |
| B. Python reference bot | Closest to the "real" play pattern (always-running program) | Second language/toolchain in a C monorepo; the *server* is the phase-1 deliverable |
| C. HTML dashboard (samhtml) | Cheap given samdashboard precedent | Read-only eye candy; scope creep away from the API-driven core |

**Recommended: A** (user-confirmed). The always-running bot pattern is
*deferred, not rejected*: §8 lists it as the first phase-2 prep task, and the
curl walkthrough in the server docs (§5.4) demonstrates the loop by hand.
**Decision:** C CLI only. (The bot re-entered v1 scope at the second review
as a *testing* autopilot — §3.7, D15.)

### 3.4 Engine Placement

| Option | Pros | Cons |
|---|---|---|
| **A. `libs/samvillage` library** | Headless determinism/golden tests without HTTP; phase 2 rehosts the same engine behind a real server; `PROJECTS.md`-visible component | Slightly more ceremony than app-internal code |
| B. Domain code inside `apps/samvillage` (samtrader pattern) | Hexagonal-app precedent exists | Phase 2 would either link an *app* or fork the engine; the HTTP-free test story gets harder |

**Recommended: A.** The engine is exactly the part phase 2 must keep.
**Decision:** library, umbrella header, plain-module layout (no ports/
adapters inside the lib — HTTP is the only port and it lives in the app).

### 3.5 Persistence

| Option | Pros | Cons |
|---|---|---|
| **A. Versioned JSON snapshot (cjson), engine-owned** | Human-readable (spec-driven: saves are inspectable artifacts); cjson already a repo dependency; engine owns its format end-to-end; diffable in tests | cjson enters the engine's dependency list; larger files than binary |
| B. Binary blob (à la samvm SVM1) | Compact; memcpy-friendly | Opaque — contrary to the inspectable-artifact goal; no reuse for the API layer |
| C. Append-only command journal + replay | Free determinism replay and audit history | Overkill for a slice; snapshots + recorded seed cover debugging needs |

**Recommended: A** (snapshots), with the seed stored so any future journal
(§6) can layer on top. The one acknowledged cost — cjson as an engine dep —
is bought back by not maintaining a parallel struct-by-struct setter API just
for loading. **Decision:** JSON snapshots, `src/samvillage_save.c`,
`SAMVILLAGE_SAVE_VERSION`.

### 3.6 Space & Movement

| Option | Pros | Cons |
|---|---|---|
| **A. No positions; travel folded into action durations** | Smallest state; queue semantics stay trivial; nothing to pathfind | No spatial gameplay; distances are just numbers |
| B. Tile grid with fog of war | Grows into phase 3 | More state, more rules, more client UI — beyond "light exploration" |
| C. Coordinates + pathing | Real geography | Needs collision/terrain rules; classic scope trap |

**Recommended: A** (user-confirmed shape: named sites, §4.10). Site
*distance* is kept as an explicit numeric property so a future grid (§6) can
compute the same numbers from real geometry without changing action
semantics. **Decision:** positionless; durations carry travel.

### 3.7 Testing Autopilot Bot (added in second review, D15)

| Option | Pros | Cons |
|---|---|---|
| **A. C app, libcurl + cjson, plays over HTTP** | Same stack as the CLI; exercises the API exactly the way phase-2 bots will (sockets, polling, JSON, error handling); runs headless as a CTest against a live server | C policy code is chattier than Python |
| B. Python script | Fastest to iterate on policy | Second toolchain in a C monorepo; not a repo test target |
| C. In-process engine loop only | No processes to manage | Proves nothing about the API experience; §9.4 already covers engine-level soak |

**Recommended: A** (user-directed). `apps/samvillage-bot` polls a live
server and keeps the village alive on its own. Role split: the §9.4 engine
soak proves the *simulation* (deterministic, in-process); the bot proves the
*play pattern* (external client over the wire). It is a test harness, not
the phase-2 product bot: no hosting, no plan persistence beyond process
lifetime (§6).

**Policy (normative).** One evaluation pass per poll (~1s), two stages:

1. *Needs, per villager, in order:* satiety < 40 and meals in stock →
   `EAT` (best available); energy < 25 → `SLEEP until full`.
2. *Work allocator, one task to each idle villager, by global priority:*
   (a) `WEED` blighted fields; (b) `WATER` moisture-0 fields; (c) `HARVEST`
   ripe fields; (d) `SOW` fallow fields with seed in stock; (e) `GRIND` /
   `BAKE` / `COOK` toward stock targets; (f) `CHOP` / `QUARRY` toward stock
   targets; (g) `WEAVE` / `TAILOR` toward the clothes target; (h) `MAINTAIN`
   buildings below the condition threshold; (i) `EXPLORE` when ≥ 2 villagers
   idle and stock is above all targets; (j) `BUILD` house when free beds ≤ 1
   and food ≥ 20; (k) else leave idle.

   Slot-aware: skips work whose slot is busy (kitchen, workshop, per-field).
   Enqueue-validates against §4.4 and reacts to the event feed (blight →
   weed, rot → replan targets).

**Policy constants** (defaults; one header, `bot_policy.h`, no logic):
eat_at 40, sleep_at 25, bread ≥ 8, soup ≥ 4, wood ≥ 12, stone ≥ 8,
clothes ≥ 2, maintain_below 40, explore_idle_min 2, build_beds_free 1,
build_food 20.

**Interface:** flags `--url`, `--token`, `--once` (single evaluation pass),
`--max-days N` (stop when reached), `--log <file>` (one line per decision).
Exit 0 when `--max-days` is reached with ≥ 1 adult alive (or all goals
achieved); nonzero otherwise. Prints a summary on exit: day reached,
villagers alive, goals achieved, stock snapshot.

## 4. Game Rules Specification (normative)

§4 is the game. Numbers are the v1 defaults; every one of them lives in the
rules tables (§4.0) and may be tuned by editing the tables + bumping
`SAMVILLAGE_RULES_VERSION` — never by scattering literals through logic.

### 4.0 Rules Tables and Versioning

**Content source of truth (v2 on): the tables live in
`libs/samvillage/spec/`** — one markdown file per topic (seasons, villagers,
actions, goods/recipes, crops/soil, buildings/monuments, events, sites,
goals, ideas), collated and version-marked there. This TRD keeps the
*mechanics contracts* (§4.1-§4.11 semantics, API, architecture); the spec
folder keeps the *numbers*. Changes flow spec → `samvillage_rules.c` →
`RULES_VERSION` bump → golden re-bless (spec README protocol). The tables
below reflect v1 as shipped.

The engine compiles the content tables as constant arrays exposed via
`samvillage_rules()`:

```c
#define SAMVILLAGE_RULES_VERSION 1

typedef struct { /* one row per §4.4 */ } SamVillageActionRule;
typedef struct { /* one row per §4.5 */ } SamVillageCropRule;
typedef struct { /* one row per §4.6 */ } SamVillageRecipeRule;
typedef struct { /* one row per §4.7 */ } SamVillageBuildingRule;
typedef struct { /* one row per §4.8 */ } SamVillageEventRule;
typedef struct { /* one row per §4.10 */ } SamVillageSiteRule;
typedef struct { /* one row per §4.13 */ } SamVillageGoalRule;

const SamVillageRules *samvillage_rules(void);  /* all tables + name/surname pools + version */
```

`GET /v1/rules` serializes the same struct — clients must read rules from
the server rather than hardcoding them (§1.3 P5).

### 4.1 Time System

| Constant | Value | Notes |
|---|---|---|
| Tick | 1 real second | simulation step; all durations are tick multiples |
| Game day | 600 real seconds (10 min) default | `--day-length` and `--time-scale` flags; time scale multiplies tick advancement (scale 10 = 10 ticks per real second for CI/soak, §9.4) |
| Game hour | day/24 = 25 real seconds | derived |
| Day/night | 06:00–20:00 day, else night | affects `SLEEP(until=dawn)` only; **no night work penalties** — energy is the only rhythm constraint, staggered 24h shifts are legal (§13 Q6) |
| Autosave | every 300 s + SIGTERM + `POST /v1/save` | §7 |

- `paused` freezes tick advancement; HTTP stays fully served. While paused,
  enqueue/replace/clear still work (offline privilege, §1.3 P6).
- Commands applied between ticks take effect at the next tick boundary.
- World time = `tick` counter; `day`/`hour` are derived (`day = tick /
  day_length_ticks`, hour = fraction × 24, day starts 00:00; a new world
  starts at day 1, 06:00).

### 4.2 World Model & Identifiers

Entities: **villagers**, **buildings** (fields are buildings), **the
stockpile** (one village-level inventory with per-category capacity, §4.7),
**sites** (off-map locations, §4.10), and the **event log** (append-only,
monotonic `event_id`).

IDs are stable strings: `vil-1`, `bld-7`, `site-3`; counters never reuse
values within a save. The event log is capped in memory at the last 10,000
entries (older entries dropped; cursor remains monotonic).

A new world (`--new`): seed chosen per §4.11 flag (or random), the starting
roster (§4.3), starting buildings (§4.7), starting stock (§4.7), and one
known site (§4.10).

### 4.3 Villagers

State per villager:

| Field | Range | Meaning |
|---|---|---|
| `stage` | `child` / `adult` | children can't act (§4.9.3) |
| `sex` | `f` / `m` | pairing (§4.9.1) |
| `family_name` | string | surname; children take the father's (§4.9.2) |
| `partner` | villager id / — | couple; pairing is passive (§4.9.1) |
| `mother`/`father` | villager id / — | family tree; founders have none |
| `household` | building id | the house whose bed this villager sleeps in |
| `health` | 0–100 | 0 = dead (removed, §4.3.2) |
| `satiety` | 0–100 | hunger; decays always |
| `energy` | 0–100 | fatigue; decays awake, recovers asleep |
| `pregnant` | — | timer in game hours; can still work (v1) |
| `skills` | farming / cooking / crafting / foraging, each level 1–5 | §4.4.3 |
| `clothes_wear` | 0–100 | passive decay, §4.6.1; children exempt until adulthood |
| `action` | current action + remaining ticks | §4.4 |
| `queue` | ≤ 4 pending actions | §4.4 |

Needs rates (per game hour):

| Rate | Value | Effect thresholds |
|---|---|---|
| Satiety decay | −4 (child −2) | < 30 *hungry*: work duration ×1.25; ≤ 0 *starving*: health −2/h |
| Energy decay awake | −5 (×1.5 if clothes ragged, §4.6.1) | < 25 *tired*: duration ×1.25; 0 *exhausted*: only SLEEP/WAIT may start |
| Energy recovery asleep | +15 | SLEEP ends at full or the cap, §4.4.1 |
| Health regen | +1 when satiety > 50 and energy > 50 | — |
| Starvation | −2 health/h at satiety 0 | full → dead in ~2 game days (D5) |

Food restores satiety: bread +60, soup +50, berries +15 (§4.6). A villager
eating twice a day (+120 vs −96/day) is stable.

#### 4.3.1 Starting Roster (fixed, part of the spec)

| id | name | sex | family | notes |
|---|---|---|---|---|
| vil-1 | Mara | f | Fell | farming 3; married to Toma |
| vil-2 | Toma | m | Fell | cooking 3; married to Mara |
| vil-3 | Bess | f | Crane | crafting 3; married to Jory |
| vil-4 | Jory | m | Crane | foraging 3; married to Bess |
| vil-5 | Anke | f | Dell | generalist (all 1) |
| vil-6 | Pell | m | Ash | generalist (all 1) |

All start adult, all needs 100, clothes 100, parents none. Households: house
`bld-1` beds Mara, Toma, Anke; house `bld-2` beds Bess, Jory, Pell. Children
inherit the father's surname and draw given names sequentially from the
rules name pool; wanderers arrive with a surname from the surname pool
(§4.9).

#### 4.3.2 Death

`health ≤ 0` (only starvation can cause this in v1): the villager dies —
event logged, queue dropped, beds freed, roster entry becomes `dead`
(retained for the log, never reused). If all adults die the world keeps
running (events still tick; §4.11 notes the loss); the slice's real end
state is "restart from a new world".

### 4.4 Actions & Queues

Every action is `{type, target?, count?, param?}`. One **running** action +
up to **4 pending** (D6). The queue survives save/load; death drops it.

Semantics:

1. **Enqueue validation** (POST/PUT): shape, target existence, queue cap,
   and cheap static preconditions (e.g. crop-specific SOW target is a
   FALLOW field). Invalid → 409 with a code (§5.2); nothing is queued.
2. **Start validation** (tick): full world check — inputs present, building
   usable and slot free, villager not exhausted (unless SLEEP/WAIT),
   capacity for outputs, field in required state. Fail → event
   `action_failed {reason}`, queue advances (fail-soft, §1.3 P4).
3. **Completion**: effects applied atomically at the completing tick; XP
   awarded; event logged for state-changing actions.

Replacing or clearing the queue never touches the running action.

#### 4.4.1 Action Catalog (durations at skill 1, in ticks)

| Type | Target | Dur | Inputs → Outputs | Notes |
|---|---|---|---|---|
| `EAT` | — | 15 | 1 bread *or* 1 soup *or* 3 berries → satiety | param `food`; default best-available in order bread, soup, berries |
| `SLEEP` | — | ≤ 200 | → energy +15/h | param `until`: `full` (default) or `dawn`; ends early at full/dawn; wear/decay continue |
| `WAIT` | — | param ≤ 300 | — | explicit idle |
| `SOW` | field | 45–60 (crop) | 1 wheat/veg/flax → field SOWN | a crop's own good is its seed — the harvest tithes ~10% back to re-sow; field must be FALLOW |
| `WATER` | field | 30 | → field moisture full | the canonical 30s action |
| `WEED` | field | 45 | → clears blight (§4.8) | |
| `HARVEST` | field | 40–90 (crop) | field → yields per §4.5 | field must be RIPE |
| `GRIND` | workshop | 40/batch | 2 wheat → 2 flour | batch actions take `count` 1–5 |
| `BAKE` | kitchen | 120/batch | 2 flour + 1 wood → 4 bread | |
| `COOK` | kitchen | 90/batch | 3 veg + 1 wood → 3 soup | |
| `WEAVE` | workshop | 150/batch | 4 flax → 2 cloth | |
| `TAILOR` | workshop | 180 | 1 cloth → 1 clothes | |
| `BUILD` | building | 120 | → +120 work-s on an under-construction building | parallel builders' work adds up; §4.7 |
| `MAINTAIN` | building | 120 | → building condition +35 (cap 100) | |
| `CHOP` | site | 60–100 (site) | → wood per site | batch, §4.10 |
| `QUARRY` | site | 90 | → 3 stone | batch |
| `FORAGE` | site | 50 | → 3 berries (2 at depleted sites) | batch |
| `EXPLORE` | — | 180–300 | → reveals next site | §4.10; duration by next site's distance |

Slots (start validation): kitchen allows 1 concurrent cook/baker;
workshop 1; one villager per field; construction sites unbounded.

#### 4.4.2 Interrupts

Only death interrupts a running action in v1 (no cancel-running API —
DELETE clears *pending* only). Rationale: keeps save/determinism simple;
revisit if play demands it (§8).

#### 4.4.3 Skills

Four domains — farming (SOW/WATER/WEED/HARVEST), cooking (BAKE/COOK),
crafting (GRIND/WEAVE/TAILOR/BUILD/MAINTAIN), foraging
(CHOP/QUARRY/FORAGE/EXPLORE). Level L:

- duration × (1 − 0.08·(L−1)) (level 5 = ×0.68),
- yield × (1 + 0.10·(L−1)), rounded half-up (level 5 = ×1.4),
- XP: +1 per completed domain action (failures earn nothing); cumulative
  thresholds per domain: level 2 at 10, 3 at 25, 4 at 50, 5 at 90.

### 4.5 Crops

Field lifecycle: `FALLOW → SOWN → GROWING → RIPE → (harvest) → FALLOW`.
Growth progresses only while soil moisture > 0. Moisture decays to 0 in
6 game hours (150 ticks); `WATER` sets it full. Unwatered = paused growth
(not death). Blight (§4.8) pauses growth until `WEED`. RIPE fields keep
indefinitely (v1; rot of standing crops is phase-2).

| Crop | Sow | Grow (watered) | Harvest | Yield | Eaten by |
|---|---|---|---|---|---|
| wheat | 60 | 2 game days | 90 | 12 wheat | — (grind) |
| veg | 45 | 1 game day | 40 | 8 veg | raw +10 (EAT param) |
| flax | 60 | 2.5 game days | 70 | 10 flax | — (weave) |

A wheat field needs ~8 waterings per growth cycle (2 days / 6h moisture)
≈ 240s of watering — the farm-labor anchor the whole economy hangs on
(§4.12).

### 4.6 Recipes, Food, Clothing

#### 4.6.1 Goods

| Good | Category | Capacity category | Notes |
|---|---|---|---|
| wheat, flour, veg, flax | food/material | food | flour & wheat rot-exposed (§4.8) |
| bread, soup, berries | food | food | meals |
| wood | material | material | fuel **and** construction |
| stone | material | material | construction |
| cloth | material | material | |
| clothes | material | material | consumed by wear, below |

**Clothing wear** (passive simulation, not an action — §1.3 P3): each
*adult* villager's `clothes_wear` decays −100 over 12 game days (children
are exempt — they outgrow garments; wear starts at 100 on maturing,
§4.9.3). At wear-out, if the stockpile holds a `clothes`, one is consumed
automatically and wear resets (accounting, not agency). If none: the
villager is *ragged* — energy decay ×1.5 (§4.3) until clothed. This makes
the flax chain a real upkeep stream.

#### 4.6.2 Recipe Table (also in `GET /v1/rules`)

See §4.4.1 rows GRIND/BAKE/COOK/WEAVE/TAILOR — inputs/outputs are part of
the action rules, not a separate concept. (SpaceTraders-shaped: the action
*is* the recipe.)

### 4.7 Buildings

All buildings have `condition` 100 → 0 by decay; at 0 the building is
`RUINED`: unusable (no sleep/craft/cook; a ruined field loses its crop and
reverts to FALLOW). `MAINTAIN` +35. Construction: `POST /v1/buildings`
deducts materials and creates the building `UNDER_CONSTRUCTION`; `BUILD`
actions add work-seconds (parallelizable); at the required work total it
flips `ACTIVE`. Cancelling construction is not supported in v1 (materials
are spent; finish or ruin it).

| Type | Cost | Work | Role / capacity | Decay |
|---|---|---|---|---|
| house | 15 wood, 5 stone | 400 | beds 3 | 45 game days |
| field | 4 wood | 90 | one crop cycle | 30 |
| kitchen | 10 wood, 2 stone | 240 | 1 cook slot | 30 |
| workshop | 12 wood, 4 stone | 300 | 1 craft slot | 30 |
| granary | 10 wood, 3 stone | 240 | +40 food capacity; halves rot losses (§4.8) | 30 |
| storehouse | 8 wood | 100 | +60 material capacity | 30 |

Stockpile capacity: food 20 + 40/granary (ACTIVE); materials 30 + 60/
storehouse. Production/gather actions fail start-validation when outputs
would overflow capacity.

**Starting buildings:** 2 houses, 2 fields, 1 kitchen, 1 workshop,
1 granary, 1 storehouse. **Starting stock:** 15 bread, 4 berries, 10 wood,
6 stone, 0 flour/cloth/clothes/veg/wheat/flax.

### 4.8 Events

Checked once per game hour by a seeded draw against the table (order:
per-event independent Bernoulli draws in table order — deterministic under
the seed). All effects log events with payloads.

| Event | p / check | Effect |
|---|---|---|
| dry_rot | 0.020 | −40% of wheat+flour in stock (−20% if any ACTIVE granary; condition ≥ 50) |
| rats | 0.015 | −40% of veg+soup (granary mitigation as above) |
| blight | per field: 0.02 (0.05 if moisture = 0) | growth paused until WEED |
| good_rains | 0.030 | all field moisture → full |
| damp_stores | 0.010 | −30% wood |
| wanderer | 0.004 (max 1 per 2 days) | adult joins if a bed is free and food ≥ 10; else logs a refusal |

Expected cadence at 24 checks/day: dry rot ~every other day, rats ~2/3 that,
rains ~daily, a wanderer roughly every 10 days. The mitigation levers
(granary, watering, stock discipline) are the "plan against events" game.

### 4.9 Population & Lineage

#### 4.9.1 Couples & pairing

Pairing is passive life simulation (like wear — not an action, §1.3 P3): at
the 06:00 check, if two unpaired adults of opposite sex exist, a
seeded-chosen pair forms a couple (event `paired`). Villagers do not
relocate on pairing in v1; households move only at birth (children join the
mother's house) and death (bed freed). Player-assigned housing is a noted
phase-3 lever (§6). The starting roster ships two couples (§4.3.1).

#### 4.9.2 Birth

Daily check at 06:00. Conditions: ≥ 1 couple with the mother-to-be adult
and not pregnant; free beds ≥ 1 (house ACTIVE); food
(bread+soup+berries) ≥ 15; population < 20. A seeded-chosen couple then
conceives: the mother is `pregnant` for 2 game days. If the mother dies,
the pregnancy is lost (event). Birth: a `child` event — given name drawn
sequentially from the rules name pool, the father's family name, household
= the mother's house.

#### 4.9.3 Children

- Satiety decay −2/h; cannot queue actions (not player units).
- Fed by a passive draw at 06:00 — 1 bread or 1 soup or 3 berries (same
  priority as EAT), accounted as an event. If the draw fails, ordinary
  starvation applies (§4.3): satiety runs out, health drains −2/h —
  **children can die of famine like anyone** (§13 Q7).
- Clothes: exempt until adulthood (they outgrow garments);
  `clothes_wear` starts at 100 on maturing.
- After 4 game days the child becomes an adult — controllable, and
  *gifted*: the domain that is highest among the parents (tie broken by a
  seeded draw) starts at level 2 with 5 XP; all others at level 1.

Population cap = beds. Alternate growth path: the wanderer event (§4.8) —
arrives single, seeded sex, surname drawn from the surname pool.

Name pools (rules tables, §4.0): ≥ 40 given names, ≥ 12 surnames, drawn
sequentially — deterministic under the seed (§4.11).

### 4.10 Sites (exploration, D7/D8)

Five fixed sites, revealed strictly in order by `EXPLORE` (duration
120 + 60×distance ticks). The first is known from world creation.

| Order | Site | Distance | Offers |
|---|---|---|---|
| 1 | Coppice | 1 | CHOP 60 → 4 wood |
| 2 | Long Meadow | 1 | FORAGE 50 → 3 berries |
| 3 | Rocky Rise | 2 | QUARRY 90 → 3 stone |
| 4 | Old Woods | 2 | CHOP 100 → 8 wood |
| 5 | The Ruins | 3 | FORAGE 50 → first 3 visits each yield a cache (+5 stone, +2 cloth); afterwards the site degrades to 2 berries |

Ruins cache visits are three `FORAGE` actions at The Ruins that each yield
the cache (tracked by a per-site counter); after three, the site degrades to
the berry row. Distances are data (D7's escape hatch for a future grid).

### 4.11 New-World Derivation

`--seed u64` (or random). The seed feeds one `SamRng` stream for all draws
(event checks, pregnancy choice, child skill, wanderer timing jitter).
Everything else in world creation is fixed by §4.3.1/§4.7/§4.10 — so the
only between-run variance is the event/population stream. Same seed + same
commands at same ticks → same world (golden tests, §9.2).

### 4.12 Balance Sanity Check (worked example, day = 600s)

One wheat field per cycle: sow 60 + watering ~240 + harvest 90 ≈ 390s of
farm labor (+ contention) → 12 wheat → 6 GRIND batches (240s) → 12 flour →
6 BAKE batches (720s, 6 wood) → 24 bread ≈ 12 villager-days of food per
2-day cycle. So ~2 fields + a part-time cook feed the starting six with
slack for children; berries (45 satiety per 50s) are the emergency valve at
~4× the labor cost of bread. Clothing: 6 villagers wear out 6 clothes per
12 days → 6 cloth → 12 flax → ~1.2 flax fields continuous + tailoring time.
The pressure is real but a single player-via-CLI can hold it — which is
what a slice should be.

### 4.13 Goal Board (D14)

A static milestone table in the rules, evaluated at each tick; each goal
flips to achieved **exactly once**, with the tick recorded. Informational
only — no rewards, no gating; the sandbox stays a sandbox. This is the
slice's legible "done" bar and the seed of phase-2 quests (§6).

| id | Goal | Predicate |
|---|---|---|
| `survive` | First light | 3 full game days elapsed with ≥ 1 adult alive |
| `bread` | Daily bread | ≥ 4 bread in stock at once |
| `clothes` | Properly dressed | ≥ 1 `TAILOR` action completed ever |
| `house` | New roof | a building created via `POST /v1/buildings` reaches ACTIVE |
| `ruins` | Delvers | first Ruins cache looted (§4.10) |
| `growth` | Hearth and home | population ≥ 8 |

`GET /v1/goals` returns id, description, achieved, achieved_tick for each.

## 5. API Specification

### 5.1 Basics

- Base URL: `http://127.0.0.1:8123/v1` (default port 8123, `--port`).
- Auth: `Authorization: Bearer <token>` on every request. Token is
  generated at world creation, stored in the save, printed at startup
  (D11). Missing/wrong → 401.
- Content: `application/json` both ways; bodies are UTF-8.
- Errors, single envelope:

```json
{ "error": { "code": "QUEUE_FULL", "message": "queue already has 4 pending actions" } }
```

Statuses: 200 read/act, 201 created, 204 cleared, 400 malformed JSON /
bad shape, 401 auth, 404 unknown path/id, 409 world-invalid (codes below),
500 engine fault. 409 codes: `QUEUE_FULL`, `INVALID_TARGET`, `BAD_STATE`,
`NO_INPUTS`, `NO_CAPACITY`, `EXHAUSTED`, `SLOT_BUSY`, `NOT_ENOUGH_MATERIALS`.

### 5.2 Endpoints

samhttp has no PATCH (D10) — state changes that aren't plain CRUD are
`POST /v1/<noun>/<verb>`.

| Method & path | Purpose |
|---|---|
| `GET /v1/meta` | version, rules_version, seed, tick, day, hour, paused, time_scale, day_length, population |
| `POST /v1/time/pause` · `/resume` | freeze / unfreeze ticks |
| `POST /v1/time/scale` `{"scale": 1–100}` | set time scale |
| `GET /v1/rules` | every §4 table verbatim |
| `GET /v1/villagers` | roster with needs, stage, skills, current action, queue length |
| `GET /v1/villagers/{id}` | full detail incl. queue |
| `GET /v1/villagers/{id}/queue` | running + pending actions |
| `POST /v1/villagers/{id}/queue` | append `{"actions": [A, …]}` (cap 4 pending) |
| `PUT /v1/villagers/{id}/queue` | replace pending (running untouched) |
| `DELETE /v1/villagers/{id}/queue` | clear pending (204) |
| `GET /v1/buildings` · `GET /v1/buildings/{id}` | state incl. condition, crop/moisture for fields, work remaining for construction |
| `POST /v1/buildings` `{"type": "house"}` | commence construction (materials deducted) → 201 |
| `GET /v1/inventory` | stock levels + capacities per category |
| `GET /v1/sites` | known sites (unknown ones absent), offers, ruins counter |
| `GET /v1/events?since=<id>` | event log tail (cursor = last `id`; default latest 100) |
| `GET /v1/goals` | milestone board: id, description, achieved, achieved_tick (§4.13) |
| `POST /v1/save` | snapshot now → 204 |

Action JSON: `{"type": "WATER", "target": "bld-3", "count": 1, "param": null}`
(`target`/`count`/`param` optional per §4.4.1).

Villager JSON (representative):

```json
{
  "id": "vil-1", "name": "Mara", "stage": "adult", "sex": "f",
  "family_name": "Fell", "partner": "vil-2", "mother": null, "father": null,
  "household": "bld-1", "health": 100, "satiety": 74, "energy": 61,
  "clothes_wear": 88, "pregnant": false,
  "skills": { "farming": 3, "cooking": 1, "crafting": 1, "foraging": 1 },
  "action": { "type": "WATER", "target": "bld-3", "remaining_ticks": 12 },
  "queue": [ { "type": "HARVEST", "target": "bld-4" } ]
}
```

### 5.3 Client Guidance

Poll `GET /v1/meta` (cheap) at ~1 Hz; poll villagers/events as needed; keep
the `since` cursor; read `GET /v1/rules` at startup and on a
`rules_version` mismatch. Plan against *needs thresholds + durations*, not
against reaction speed — the tick is 1s and actions are minutes.

### 5.4 Walkthrough (ships in `apps/samvillage/README.md`)

A curl session that: reads meta, queues Mara to water-then-harvest, starts a
house, sleeps the village, and reads the event feed — the by-hand version
of the bot loop the CLI automates.

## 6. Phase 2/3 Guiding Star (out of v1, shaping v1)

Future work this design explicitly keeps cheap (do not build now):

| Future | v1 hook already in place |
|---|---|
| Hosted multi-player world (phase 2) | engine is a headless lib; HTTP is one adapter; bearer-token seam; commands apply at tick boundaries (natural serialization point for concurrent players) |
| Push updates | event log already has a monotonic cursor; a websocket layer replays the same feed |
| Offline progression / tick catch-up | time is a pure tick counter; catch-up is "run more ticks" |
| Rate limiting, per-player auth | 401/409 envelope and error codes already structured |
| Grid map / geography (phase 3) | site `distance` is data; action durations can be recomputed from geometry without semantic change |
| Production-depth (phase 3) | recipes are table rows; new chains add rows, not code paths |
| Always-on hosted product bot (phase 2) | the C autopilot (§3.7) already exercises every API call over the wire; phase 2 adds hosting, persistence, and always-on operation |
| Deeper family/household gameplay (phase 3) | lineage state (parents, couples, households) is already first-class; player-assigned housing is the natural lever |
| Quests/achievements (phase 2/3) | goal board predicates (§4.13) are the pattern to extend |

Honest caveats, recorded now: single-threaded polling won't survive many
players (fine — phase 2 rehosts); village-level stockpile (not per-building
storage) is an abstraction phase 3 may want to revisit; children-as-cost
(not micro-manageable units) is a v1 simplification phase 3 may invert.

## 7. Save Format

JSON object, top-level:

```
{ save_version, rules_version, seed, tick, paused, time_scale, day_length,
  token, id_counters, villagers[], buildings[], stock, sites_known,
  ruins_visits, event_log_tail[], event_id_counter }
```

- Path: `./samvillage-save.json` default, `--save` override. Atomic write
  (tmp + rename). Load on start if present; `--new` (with optional `--seed`)
  starts fresh (refuses to overwrite an existing save without `--force`).
- `save_version`/`rules_version` mismatches on load → clear error, no
  partial loads.
- Running actions are saved with `remaining_ticks`; queues verbatim.
- The token lives in the save (single-user offline convenience; phase 2
  moves auth out — §6).

## 8. Out of Scope for v1

| Feature | Reason |
|---|---|
| Combat / enemies / PvE | Phase 3; explicitly unwanted now |
| Multi-player, hosted world, real auth | Phase 2 |
| Websockets / push | Phase 2; cursor feed is the hook |
| Market / trading | No second party to trade with |
| Seasons, weather system | **Superseded in v2** — seasons + winter specified at `spec/time-and-seasons.md` (§14); plain weather warnings still parked in `spec/ideas.md` |
| Villager autonomy / AI priorities | Villagers are dumb executors (§1.3 P3) — the game *is* the client's brain |
| Positions, pathfinding, movement | Decided against (§3.6) |
| Tools/weapons/items beyond clothes | **Superseded in v2** — tools + wear specified at `spec/goods-and-recipes.md` (§14); weapons still phase 3 |
| Old-age death, disease | Starvation is the only death (D5) |
| Cancel-running-action | §4.4.2 — deliberate simplicity |
| Always-on *hosted* bot (product, not test) | The v1 autopilot (§3.7) is a local test harness only; hosted/persistent bots are phase 2 |
| Rate limiting, request quotas | One local client |

## 9. Test Plan

Plain C, `assert()` + `printf()`, one binary per file, CTest — house style.

### 9.1 Unit — `libs/samvillage/test/samvillage_test.c` → `samvillage_unit`

| Test | Description |
|---|---|
| `test_time_math` | tick→day/hour derivation; pause freezes; scale multiplies |
| `test_needs_decay` | satiety/energy rates; thresholds apply duration penalties; exhausted gating; starvation −2/h; regen conditions |
| `test_death_and_drop` | health ≤ 0 → dead, queue dropped, bed freed, all-adults-dead world still ticks |
| `test_queue_caps` | append beyond 4 → QUEUE_FULL; replace/clear leave the running action alone |
| `test_action_validation` | enqueue-time and start-time failures produce the right 409 codes; fail-soft advances the queue |
| `test_skills` | duration/yield multipliers; rounding half-up; XP thresholds |
| `test_crop_cycle` | moisture decay pauses growth; WATER resumes; blight+WEED; RIPE persists; harvest yields ×skill |
| `test_recipes` | every batch action consumes/produces exactly; capacity overflow fails start |
| `test_buildings` | construction work accumulation incl. parallel builders; decay to RUINED blocks use; MAINTAIN caps at 100 |
| `test_clothes_wear` | wear-out consumes stock when present; ragged penalty otherwise |
| `test_events` | seeded stream reproduces exactly; granary halves rot; wanderer gates (bed, food, cooldown) |
| `test_population` | pairing; birth conditions; pregnancy timer and loss on mother's death; surname + household inheritance; inherited aptitude at maturity; child feeding draw incl. famine death; children can't queue |
| `test_goals` | each §4.13 predicate flips exactly once, at the right tick; unreachable states stay false |
| `test_sites` | strict reveal order; EXPLORE duration by distance; ruins 3 caches then degrades |
| `test_save_roundtrip` | save → load → identical state hash; running-action remaining_ticks survives; version mismatch rejected |
| `test_rules_tables` | compiled tables match §4 constants (golden numbers) |

### 9.2 Determinism — `test/samvillage_golden.c` → `samvillage_golden`

Headless engine, fixed seed, a scripted command schedule (the same JSON the
API would deliver, applied at fixed ticks), run 3 game days, compare
`samvillage_world_hash()` against a committed golden value. `--bless`
regenerates (dev tool, never CTest). Any rules-table change that breaks the
golden must be a conscious `--bless` + `RULES_VERSION` bump.

### 9.3 API Integration — `apps/samvillage/test/api_test.c` → `samvillage_api`

Boots the server in-process on an ephemeral port (samhttp), drives it with
raw HTTP (in-repo loopback client or libcurl), asserts: 401 without token;
each endpoint's happy path; each 409 code reachable; event cursor
pagination; pause/scale take effect; autosave+`POST /v1/save` produce a
loadable file.

### 9.4 Acceptance Soak — `test/soak_test.c` → `samvillage_soak`

Time scale 10 (3 game days ≈ 3 real minutes). A scripted "minimal bot"
(same command-scheduler code as the golden test, but reactive — reads needs,
queues water/eat/sleep) keeps the starting six alive for 3 game days,
produces ≥ 1 bread and ≥ 1 clothes, starts a house, observes ≥ 3 distinct
events, and achieves ≥ 4 goals (§4.13). Asserts all six alive at the end.
Generous CTest timeout; excluded from Valgrind runs.

The slice is *done* when 9.1-9.5 pass and the CLI walkthrough (§5.4) reads
cleanly.

### 9.5 Bot Soak — `apps/samvillage-bot/test/soak.c` → `samvillage_bot_soak`

A supervisor test that plays the real game end-to-end over HTTP: boots
`apps/samvillage` on an ephemeral port (temp save dir, fixed seed,
time scale 10), launches `samvillage-bot --max-days 3`, waits, then asserts
via the API: bot exited 0, ≥ 4 goals achieved (`GET /v1/goals`), ≥ 1 adult
alive. Determinism is *not* asserted here — external client, real sockets;
the engine golden (§9.2) owns determinism. Generous CTest timeout; excluded
from Valgrind runs. Doubling as the endurance tool: `--max-days` raised by
hand for overnight runs.

### 9.6 Depth Soak (v2 bar) — `samvillage_depth_soak`

Two runs, same seed, default v2 rules, one full year (24 game days, scale
10 ≈ 24 min):

1. **The frozen v1 greedy policy must fail.** The autopilot's v1 policy,
   preserved verbatim as `bot_policy_v1.c`, has to end the year short of
   the bar (villager death, or < 4 v2 goals) — proof the rules now demand
   planning.
2. **A planning policy must pass.** The upgraded bot (rotation-aware,
   tool-crafting, winter-prepping, feast-scheduling) must finish the year
   with all founders-or-descendants alive and ≥ 6 v2 goals.

This test *is* the v2 acceptance definition — mechanics land only when both
halves hold.

## 10. CMake

```cmake
# libs/samvillage/CMakeLists.txt
ptah_add_library(samvillage
    SOURCES
        src/samvillage.c            # lifecycle, world create/load
        src/samvillage_time.c       # §4.1
        src/samvillage_rules.c      # §4 tables + samvillage_rules()
        src/samvillage_villager.c   # §4.3, §4.9
        src/samvillage_action.c     # §4.4
        src/samvillage_crop.c       # §4.5
        src/samvillage_building.c   # §4.7 incl. inventory
        src/samvillage_events.c     # §4.8
        src/samvillage_sites.c      # §4.10
        src/samvillage_save.c       # §7 (cjson)
    PUBLIC_HEADERS
        include/samvillage.h
    DEPENDENCIES
        samrena
        samdata
        samstring
        cjson
)

# apps/samvillage/CMakeLists.txt
ptah_add_executable(samvillage
    SOURCES src/main.c src/api.c src/http_json.c
    DEPENDENCIES samvillage samhttp samplex samrena samdata samstring cjson)

# apps/samvillage-cli/CMakeLists.txt
ptah_add_executable(samvillage-cli
    SOURCES src/main.c
    DEPENDENCIES cjson)
# + find_package(CURL) — libcurl, mirroring samtrader

# apps/samvillage-bot/CMakeLists.txt — same client-only rule as the CLI
ptah_add_executable(samvillage-bot
    SOURCES src/main.c src/policy.c
    DEPENDENCIES cjson)
# + CURL; test/soak.c supervisor registers ctest samvillage_bot_soak (§9.5)
```

Tests wired per §9 in the same files, mirroring the samvm CTest pattern.

## 11. Documentation Updates (at implementation time)

- `PROJECTS.md`: `samvillage` library entry + both app entries + dependency
  graph lines.
- `apps/samvillage/README.md`: run/flags, the §5.4 curl walkthrough, save
  file location, token handling.
- `apps/samvillage-cli/README.md` (or `--help` as the doc): commands, config
  file (~/.config/samvillage/cli.json: port + token).
- `apps/samvillage-bot/README.md`: flags, policy constants (§3.7), running
  against a live server, and the endurance/overnight recipe (§9.5).
- AGENTS.md: no change (umbrella-header rule followed).

## 12. Implementation Order

Tracked in samtasker (project `PTAH`); each ticket: `in-progress` →
commit & push → `done`, build green throughout.

| # | Ticket | Depends on |
|---|---|---|
| 1 | Engine skeleton: world create, time/tick/pause/scale, needs decay + death; unit tests | — |
| 2 | Rules tables (`samvillage_rules`) with §4 constants (incl. goal table + name pools) + golden-numbers test | 1 |
| 3 | Actions & queues: validation, fail-soft, skills/XP; unit tests | 2 |
| 4 | Crops, recipes, inventory capacity, buildings (construction/decay/maintain); unit tests | 3 |
| 5 | Events scheduler + population (pairing, pregnancy, lineage, children, wanderer) + goal board; unit tests | 4 |
| 6 | Sites/exploration + clothes wear; unit tests | 4 |
| 7 | Save/load (cjson) + determinism golden test (`--bless`) | 5, 6 |
| 8 | `apps/samvillage` server: endpoints (incl. `/v1/goals`), auth, error envelope, autosave; API integration tests | 7 |
| 9 | `apps/samvillage-cli` client + README walkthroughs | 8 |
| 10 | Acceptance soak test + PROJECTS.md/docs sweep | 8, 9 |
| 11 | `apps/samvillage-bot` autopilot skeleton: HTTP client, poll loop, needs policy (eat/sleep), decision log, exit summary | 10 |
| 12 | Bot work allocator: full §3.7 priority order, slot-awareness, stock targets, `bot_policy.h` constants | 11 |
| 13 | Bot soak supervisor test (`samvillage_bot_soak`, §9.5) + bot README | 12 |

## 13. Resolved Decisions (confirmed in review)

| # | Question | Resolution |
|---|---|---|
| Q1 | Failure stakes for neglected needs | **Slow starvation death** (D5): health drains ~2 game days at satiety 0; deaths permanent; no old-age death |
| Q2 | Game day length (real time) | **10 minutes** default (D4); `--day-length` / `--time-scale` flags exist regardless |
| Q3 | Client deliverables | **C CLI only** (D12); reference bot consciously deferred to phase-2 prep |
| Q4 | Exploration shape | **Named off-map sites** (D8), fixed list, revealed in order; grid map deferred |
| Q5 | Family/birth modeling | **Full lineage tracking** (D13): sexes, passive pairing, surnames, parents, households, inherited aptitude |
| Q6 | Night rules beyond energy | **None** — energy-only; staggered 24h shifts are legal, night stays cosmetic |
| Q7 | Children during famine | **Children can starve too** — failed feeding draw falls through to ordinary starvation rules |
| Q8 | Goal system in the slice | **Goal board** (D14): six static milestones at `GET /v1/goals`, informational only |
| Q9 | Autopilot bot (second review) | **In scope as a C test harness** (D15): `apps/samvillage-bot` plays over HTTP; policy §3.7; soak §9.5. The hosted product bot stays phase 2 |
| Q10 | v2 depth mechanics | **All four packages** (pressure+spoilage, tools+specialization, soil+rotation, projects+trader), collated in `libs/samvillage/spec/` (D16) |
| Q11 | Seasons | **Yes** — 4 seasons × 6 days, winter growth-freeze + heating + feasts (`spec/time-and-seasons.md`) |
| Q12 | Long-term grind ladder | **Civic monuments** — well / shrine / great hall as capability-unlocking projects (`spec/buildings-and-monuments.md`) |

Post-review fixes folded into the same revision: SOW seed source defined
(crops are their own seed, ~10% harvest tithe), Old Woods yield corrected to
8 wood (it was strictly worse than the starting Coppice), pregnancy lost on
the mother's death, children exempt from clothes wear until adulthood, and
name/surname pools sized in the rules tables.

## 14. v2 Depth Pass (specified in `libs/samvillage/spec/`, unbuilt)

The v1 slice proved the loop; the v1 greedy bot beating it proved the game
has no teeth. v2 adds them. Scope confirmed at the depth review (§13
Q10-Q12); every table lives in the spec folder (D16) — this section is the
map, not the numbers.

| Package | Adds | Spec |
|---|---|---|
| Pressure + spoilage | needs −5/h, yields nerfed, per-good shelf life (bread 2 days, porridge 10), `rotted` events, granary mitigation | `villagers.md`, `goods-and-recipes.md` |
| Tools + specialization | 7 craftable tools with wear + auto-equip-best, skill ladder 1-7, one locked domain per villager | `actions.md`, `villagers.md` |
| Soil + rotation | per-field fertility, wheat −35 / flax −20 / veg +5 per cycle, fallow +25/day, yield × (0.5 + fertility/200), SOW blocked < 25 | `crops-and-soil.md` |
| Projects + trader | Village Well / Shrine / Great Hall monuments, bakery + cellar upgrades, material-costing MAINTAIN, seeded tinker trader with 1-day offer windows | `buildings-and-monuments.md`, `events.md` |
| Seasons | Seedfall / Highsun / Harvesthome / Frostwane × 6 days; winter growth-freeze, heating wood sink, seasonal feast at every turn | `time-and-seasons.md` |
| Goals v2 | 10 new goals incl. winter survived, a year of feasts, all three monuments, husbandry, trade | `goals.md` |

**Design bar (the acceptance test is §9.6):** the frozen v1 greedy policy
must *fail* default v2 rules over a year, and a planning policy must pass.
API deltas are additive: `GET /v1/trader` + `POST /v1/trader/accept`,
fertility/season/trader fields on existing resources, tools in the
inventory view, `RULES_VERSION` → 2. Engine work is additive on the same
tables and tick loop — no architecture change; the positions-free model,
queue semantics, and fail-soft contract all hold.

Out of scope for v2 (parked in `spec/ideas.md`): explicit tool allocation,
beans crop, more monuments, herding, brewing, region expansion, weather
warnings, elder mentoring — plus everything still fenced off for
phase 2/3 (§8).
