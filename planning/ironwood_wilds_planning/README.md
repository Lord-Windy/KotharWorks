# Ironwood Wilds — Master Architecture & Implementation Suite

> **Planning & Review Lead:** Gemini 4 Carbon (`google/gemini-4-carbon`)  
> **Target Repository:** `kotharworks` (Pure Java 25 LTS modular monorepo)  
> **Role of This Suite:** Normative specification for `st` ticket decomposition (`KOTHARWORKS`), implementation handoffs, deterministic golden tests, and post-implementation code reviews.

---

## 1. Why This Architecture Looks the Way It Does

Every boundary in this suite is anchored directly to **three core project constraints**:

1. **The "Friday Cadence" Constraint (Momentum & Reviewable Bricks):**  
   Solo Friday build sessions demand small, high-leverage vertical bricks with **zero framework yak-shaving** (no Spring, no Hibernate, no React/npm toolchain, no custom YAML parser bugs, no hand-rolled WebSocket frame-masking boilerplate). Every ticket cut from this plan compiles cleanly under `-Werror` and ends in a deterministic, mechanically verifiable acceptance test.
2. **Emergence Needs Friction, Scarcity, Goals, and Human Texture:**  
   Pure sandboxes where agents wander around aimlessly look busy for five minutes and then flatten into noise. **Ironwood Wilds** pairs **OSRS-style mortal character skills**, a **Household Tech Tree**, **3-Ring Hold fortification against non-lethal raids**, **lethal PvM expeditions**, **True Ironwood scarcity**, and a **Homeland Tithe Ship economy**—while giving every patient settler a **procedurally generated persona, voice, scars, and first-person journal** so losing a veteran in a barrow delve actually stings.
3. **Replay-Grade Determinism Now = Effortless Genetic Algorithms Later:**  
   If game rules leak into HTTP handlers, floating-point drift, `HashMap` iteration order, or `Instant.now()` wall-clock calls, you can neither replay production bugs nor fast-forward genetic algorithms. By isolating the entire simulation inside a **pure, integer-only, logical-millisecond Java 25 engine** backed by **lazy gauges + a deterministic `(dueAtMs, seq)` min-heap** and **Command-Log-as-Truth persistence**, any live Chronicle replays bit-for-bit (`WorldHash`)—and a future GA harness (`libs/samvm` + `libs/samgenai`) can evaluate 7 simulated days in **milliseconds** on Virtual Threads.

---

## 2. The Modular Banner (`kotharworks` & `sam*` Ecosystem)

**Ironwood Wilds** is the game; `kotharworks` (`com.kotharworks`) houses both the reusable `sam*`/core libraries and the Ironwood Wilds modules in pure Java 25 LTS (`-XX:+UseCompactObjectHeaders`, Virtual Threads, `record`s, `sealed` interfaces):

```text
kotharworks/  (Maven reactor: com.kotharworks)
├── libs/
│   ├── procgen/                           Zero-dep seeded spatial math, NameGrammar, Personas & Prose
│   ├── samsim/           (optional later) Generic discrete-event min-heap, integer LazyGauge & tables
│   ├── wilds/                             Pure deterministic Ironwood Wilds engine (zero I/O, zero floats, zero Auth)
│   ├── auth/                              Modular AuthN & HTTP security (OIDC/Keycloak + Dev-Local JWT + Scoped Keys)
│   ├── samvm/            (Phase 2 - GA)   Register VM port (8-byte instructions, deterministic math)
│   └── samgenai/         (Phase 2 - GA)   LGP population/tournament/crossover trainer over headless sims
└── apps/
    ├── wilds-headless/                    CLI rig: fast-forward runs, log replay + WorldHash, ASCII gallery
    ├── wilds-server/                      Hexagonal host: Virtual-Thread HTTP/SSE + WildsAuthorizer + SSR UI + Postgres
    ├── wilds-bot/                         Reference heuristic bot & soak-test client (HTTP/SSE only; Doctrine seam)
    └── wilds-arena/      (Phase 2 - GA)   Parallel batch evolution + telemetry exporter for content/plots
```

> **Module Discipline:** Start directly inside the existing `libs/procgen`, `libs/wilds`, and `apps/wilds-server` modules plus `apps/wilds-headless` (in M0), `libs/auth` (in M4), and `apps/wilds-bot` (in M5). Keep `com.kotharworks.wilds.sim` as a clean internal package inside `libs/wilds` until a second simulation project warrants extracting `libs/samsim`.

---

## 3. Architectural Map at a Glance

![Ironwood Wilds System Architecture](diagrams/01-system-architecture.svg)

> *Diagram artifacts:* [`01-system-architecture.dot`](diagrams/01-system-architecture.dot) · [`SVG`](diagrams/01-system-architecture.svg) · [`PNG`](diagrams/01-system-architecture.png)

### Interactive Webpage Prototype Preview
Open [`prototype/index.html`](prototype/index.html) in any browser to click through the live tabs (`1`, `2`, `3`, `H`, `E`, `O`), inspect procedural character personas & journals, or view the rendered screenshots in [`04-WebInterfaceAndProtocols.md`](04-WebInterfaceAndProtocols.md):

![Ironwood Wilds Character Command Deck Prototype](prototype/ui-01-character-deck.png)

### ASCII Reference Topology

```text
  HUMANS (Browser / Phone)         BOTS (Any Language)                CLI REPLAY & PHASE 2 GA
  Multi-Tab SSR HTML + SSE         REST + SSE (or optional TCP)       wilds-headless / wilds-arena
             │                                  │                                  │
             └────────────────┬─────────────────┘                                  │
                              ▼  HTTPS: POST /api/v1/commands + GET /api/v1/stream │
┌─────────────────────────────────────────────────────────────────────────┐        │
│ apps/wilds-server  (Hexagonal Application Layer — Java 25 Virtual VTs)  │        │
│                                                                         │        │
│  ┌──────────────────┐   ┌──────────────────┐   ┌─────────────────────┐  │        │
│  │ Web UI Adapter   │   │ REST JSON Routes │   │ SSE Event Stream    │  │        │
│  │ (SSR Tabs + 250  │   │ (/api/v1/* +     │   │ (/api/v1/stream     │  │        │
│  │  LOC Vanilla JS) │   │  POST /commands) │   │  ?since=cursor)     │  │        │
│  └────────┬─────────┘   └────────┬─────────┘   └──────────┬──────────┘  │        │
│           └──────────────────────┼───────────┬────────────┘             │        │
│                                  ▼           │ Lock-Free WorldView      │        │
│                   ┌──────────────────────────┴──┐                       │        │
│                   │ libs/auth + WildsAuthorizer │───► Postgres: `auth`  │        │
│                   │ (OIDC/Dev JWT + iw_bot_ Key)│     (0 Passwords!)    │        │
│                   └──────────────┬──────────────┘                       │        │
│                                  ▼                                      │        │
│                   ┌─────────────────────────────┐                       │        │
│                   │ WorldHost (Single-Writer    │───► Postgres:         │        │
│                   │ Mailbox + 1 Hz Live Pump +  │     `wilds_world`     │        │
│                   │ Synchronous cmdId WAL)      │     (WAL + Snapshots) │        │
│                   └──────────────┬──────────────┘     & `wilds_meta`    │        │
└──────────────────────────────────┼──────────────────────────────────────┘        │
                                   │ Pure Java Calls (Zero I/O Below This Line)    │
                                   ▼                                               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ libs/wilds  (100% Pure Deterministic Simulation Core — Zero I/O, Zero Wall-Clock)       │
│                                                                                         │
│  • Dense Array Entity Tables + Generational IDs `(index, generation)` · Zero Floats!    │
│  • Integer `LazyGauge` + `DueEventHeap(dueAtMs, seq)` + O(1) `heapTicket` Cancellation  │
│  • Compile-Time `RuleRegistry` + `ActionMechanic<P>` + Raid/PvM Autobattler Strategies  │
│  • `WorldHash` Structural Hashing + Golden Replay Gate (`-DblessGolden=true`)           │
└──────────────────────────────────┬──────────────────────────────────────────────────────┘
                                   │
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│ libs/procgen  (100% Pure Seeded World, Persona & Prose Engine — Zero Runtime Deps)      │
│                                                                                         │
│  • Domain-Separated `WorldSeed` Streams -> Elevation, Hydrology, Biomes & Waypoint Map  │
│  • Chronicle Myth Flags + True Ironwood Scarcity (`<= 2` Groves per Continent)          │
│  • Phonotactic `NameGrammar` + `PersonaGenerator` + First-Person `JournalComposer`      │
│  • Hierarchical Category/Slot/Template Prose (`glance` / `look` / `examine`)            │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Key Architectural Decisions Digest

| ID | Area | Normative Decision | Why It Fits |
| :--- | :--- | :--- | :--- |
| **AR-1** | **Determinism & State** | **Dense Array Entity Tables** keyed by generational `record CharacterId(int index, int generation)`, **100% integer/permille math** (zero `float`/`double` in `WorldState`), verified by `WorldHash` golden replays. | Eliminates pointer spaghetti, stale-handle bugs, hash-order nondeterminism, and float drift across replays and GA forks. |
| **AR-2** | **Lazy Time & Heap** | **Integer `LazyGauge` interpolation + `(dueAtMs, seq)` Min-Heap + 1 Hz live pump** (`PAUSE_WHILE_DOWN` on server maintenance). | O(1) idle world cost, instant headless fast-forward (`advanceToNextEvent`), exact shared-resource causality, and 4-line O(1) `heapTicket` cancellation. |
| **GP-1** | **"Patient Hands, Living People"** | **Zero behavioral idle autonomy** (`IDLE_PATIENT` when queue empties), **rich procedural `CharacterPersona`** (origins, temperaments, scars, first-person journals). | Humans, bots, and GAs stay 100% in command of workshop scheduling, while settlers feel like memorable mortal people rather than numbered drones. |
| **GP-2** | **World & Stakes** | **Unresolved Rival Mystery + Chronicle Myth Flags**, **True Ironwood scarcity (`≤2` groves)**, **Scheduled Homeland Tithe Ship**, **non-lethal 3-Ring Hold Raids**, and **lethal PvM Party Expeditions**. **Zero PvP.** | Creates interlocking economic/military trade-offs, dramatic public/fogged Chronicle rumors, and walk-away safety. |
| **CM-1** | **Unified Wire API** | **Single mutation endpoint (`POST /api/v1/commands`)** with UUIDv7 `cmdId` idempotency + optional **`expectedRevision` (`409 STALE_STATE`)**, validated synchronously inside `WorldHost`, paired with **SSE (`GET /api/v1/stream?since=cursor`)**. | Zero UI-private endpoints, safe bot retries, ETag-equivalent lost-update protection across tabs, and ~25-line Virtual-Thread SSE push. |
| **AU-1** | **Modular Auth (`libs/auth`)** | **Zero passwords in game DB:** **Nimbus JOSE+JWT** OIDC verification (**Roles on JWT + JIT `(iss, sub) -> UserId` UUID**), loopback-fused `dev-local` -> drop-in **Keycloak** (`--auth-mode=oidc`), BFF cookie + CSRF, and House-scoped `iw_bot_...` keys. | Separates AuthN from AuthZ, tests the exact Keycloak JWT path from Day 1 with zero Docker, and enforces 12 mechanical `AuthSecurityGateTest` invariants. |
| **PS-1** | **Persistence** | **100% Append-Only `wilds_world` (`command_wal` + `CLOCK_ADVANCE` + Snapshots, zero `UPDATE` tables)** with **Commit-Before-Mutate** discipline across **3 Postgres schemas** (`auth`, `wilds_meta`, `wilds_world`) via JDBI + ~100-line SQL migrator. | Eliminates async entity-sync races, split-brain RAM/DB drift on `SQLException`, and SSE cursor time-rewinds on `kill -9`. |

---

## 5. Document Guide

1. **[IronwoodWildsGameplay.md](IronwoodWildsGameplay.md)**  
   The game & worldbuilding brief: the besieged Homeland and unresolved rival expedition, Chronicle Myth resets, **"Patient Hands, Living People"** (procedural personas, temperaments, scars, and first-person journals), OSRS skills × Household Tech Tree, True Ironwood scarcity, the Scheduled Tithe Ship, lethal PvM expeditions, and non-lethal 3-Ring Hold raids.
2. **[01-ServerArchitectureAndPatterns.md](01-ServerArchitectureAndPatterns.md)**  
   Dense flat entity schemas with generational IDs, bridging classic GoF patterns (`Command`, `Strategy`, `Prototype`, `Observer`) with Data-Oriented Java 25, compile-time `RuleRegistry`, single-writer Virtual-Thread concurrency, and strict `WorldHash` determinism gates.
3. **[02-CommandsAndLazySimulation.md](02-CommandsAndLazySimulation.md)**  
   The `POST /api/v1/commands` envelope, UUIDv7 `cmdId` idempotency, optional `expectedRevision` (`409 STALE_STATE`), Commit-Before-Mutate `WorldHost` admission + `CLOCK_ADVANCE` markers, integer `LazyGauge` + `DueEventHeap`, Two-Phase fail-soft validation, O(1) `heapTicket` cancellation, and lazy Party muster sync.
4. **[03-ProceduralGeneration.md](03-ProceduralGeneration.md)**  
   Four deterministic scales in `libs/procgen`: multi-pass geography & precomputed Site/Waypoint travel graphs, Chronicle Myth flags, PvM Lair room grammars, **Character Personas & First-Person Journals (`NameGrammar`, `PersonaGenerator`, `JournalComposer`)**, and `procgen_mvp` hierarchical `glance`/`look`/`examine` prose.
5. **[04-WebInterfaceAndProtocols.md](04-WebInterfaceAndProtocols.md)**  
   Unified REST + SSE client contract, fog-of-war projections & fogged public rumors, the server-rendered Multi-Tab Character Deck (`prototype/index.html`, **"While You Were Away"** digest, Character Journal pane, client-side 60fps countdowns), and the public **`/chronicle` Observatory**.
6. **[05-AccountsAndPersistenceSchemas.md](05-AccountsAndPersistenceSchemas.md)**  
   Three-schema PostgreSQL DDL (`auth` zero-password OIDC links & scoped `iw_bot_...` keys, `wilds_meta` `UserId`-owned Houses & replay version-triple, 100% append-only `wilds_world` `command_wal` + `CLOCK_ADVANCE` + snapshots), forward SQL migrations, and zero-rewind `PAUSE_WHILE_DOWN` restart semantics.
7. **[06-VerticalSliceAndRoadmap.md](06-VerticalSliceAndRoadmap.md)**  
   Slice 0 (*"Hearth, Iron, and the Palisade"*), worked transcript ending in `wilds-headless replay --hash`, exact Java package skeleton, Milestones **M0–M5** mapped across Fridays, the **`st` ticket backlog**, **post-implementation code-review checklist**, and Phase 2 `SamVM` + `SamGenAI` integration.
8. **[07-AuthAndSecurityArchitecture.md](07-AuthAndSecurityArchitecture.md)**  
   Standalone **`libs/auth`** (`com.kotharworks:auth`), 3-layer AuthN vs. AuthZ boundary, JWT roles + JIT `UserId` linking, loopback-fused `dev-local` -> drop-in **Keycloak** OIDC, BFF `__Host-kw_session` CSRF guards, House-scoped Bot keys, and the 12-point **`AuthSecurityGateTest`** verification suite.

### Visual Artifacts Index
- **Graphviz `.dot` Sources + Compiled `.svg` & `.png` Graphs (`diagrams/`):**
  - `01-system-architecture` ([`.dot`](diagrams/01-system-architecture.dot) · [`.svg`](diagrams/01-system-architecture.svg) · [`.png`](diagrams/01-system-architecture.png))
  - `02-entity-schema-and-patterns` ([`.dot`](diagrams/02-entity-schema-and-patterns.dot) · [`.svg`](diagrams/02-entity-schema-and-patterns.svg) · [`.png`](diagrams/02-entity-schema-and-patterns.png))
  - `03-command-and-lazy-heap-flow` ([`.dot`](diagrams/03-command-and-lazy-heap-flow.dot) · [`.svg`](diagrams/03-command-and-lazy-heap-flow.svg) · [`.png`](diagrams/03-command-and-lazy-heap-flow.png))
  - `04-gameplay-loops-and-risk` ([`.dot`](diagrams/04-gameplay-loops-and-risk.dot) · [`.svg`](diagrams/04-gameplay-loops-and-risk.svg) · [`.png`](diagrams/04-gameplay-loops-and-risk.png))
  - `05-procgen-pipeline` ([`.dot`](diagrams/05-procgen-pipeline.dot) · [`.svg`](diagrams/05-procgen-pipeline.svg) · [`.png`](diagrams/05-procgen-pipeline.png))
  - `06-postgres-three-schemas` ([`.dot`](diagrams/06-postgres-three-schemas.dot) · [`.svg`](diagrams/06-postgres-three-schemas.svg) · [`.png`](diagrams/06-postgres-three-schemas.png))
  - `07-friday-roadmap` ([`.dot`](diagrams/07-friday-roadmap.dot) · [`.svg`](diagrams/07-friday-roadmap.svg) · [`.png`](diagrams/07-friday-roadmap.png))
  - `08-auth-and-security-pipeline` ([`.dot`](diagrams/08-auth-and-security-pipeline.dot) · [`.svg`](diagrams/08-auth-and-security-pipeline.svg) · [`.png`](diagrams/08-auth-and-security-pipeline.png))
- **Interactive HTML Webpage Prototype & Views (`prototype/`):**
  - [`prototype/index.html`](prototype/index.html) *(Interactive prototype — press `1`, `2`, `3`, `H`, `E`, `O`)*
  - [`prototype/ui-01-character-deck.png`](prototype/ui-01-character-deck.png) · [`prototype/ui-02-hold-raid-defense.png`](prototype/ui-02-hold-raid-defense.png) · [`prototype/ui-03-expeditions-and-chronicle.png`](prototype/ui-03-expeditions-and-chronicle.png) · [`prototype/ui-04-observatory-showoff.png`](prototype/ui-04-observatory-showoff.png) · [`prototype/ui-frontier-map.svg`](prototype/ui-frontier-map.svg)
