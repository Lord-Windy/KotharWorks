# Ironwood Wilds (`samwilds`) — Master Architecture & Planning Suite

**Prepared by:** Gemini 4 Carbon (`google/gemini-4-carbon`)\
**Target Monorepo:** Ptah (`/home/sam/dev/ptah`, C11, arena-first)\
**Status:** Pre-implementation architectural plan (zero repository files
modified)

______________________________________________________________________

## 1. Why This Project Fits Your Exact Intersections

Mr. Stansfield's Year 10 advice holds up because **Ironwood Wilds** is not a
random shot in the dark — it is the convergence of pieces you have *already*
been building across Ptah:

| Your Interest                                    | What Exists in Ptah Today                                                                                                    | Role in Ironwood Wilds                                                                                                                                                                                |
| ------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Emergence & "Patient Sims" management**        | `libs/samvillage` (needs, skills, short queues, deterministic RNG, rules-as-data, C autopilot bot)                           | Evolves from a single offline village into a multi-household online frontier with skills, Hold building, raids, and PvM parties                                                                       |
| **Dwarf Fortress-style text worlds**             | `planning/procgen_mvp.md` + `libs/samstring` (`SamTemplate`, builders) + `planning/samsimulator.md` (seeded `f(x,y)` fields) | Becomes `libs/samprocgen`: deterministic seeded geography + hierarchical category/slot text composition driven by live world state                                                                    |
| **Genetic Algorithms & Bot Ecosystems**          | `libs/samvm` (deterministic register VM) + `libs/samgenai` (LGP `reset`/`observe`/`act`/`done` environment loop)             | Because the simulation is lazy/event-driven rather than per-second ticked, headless GA episodes can fast-forward **days of game time in milliseconds** over the exact same command struct humans use  |
| **Hypothesis Testing ("Stats Population Tool")** | `samgrognard` soak/cohort runners & gate reports                                                                             | Every season/world boots from a versioned `WorldHypothesisConfig` (resource scarcity, Homeland tribute multipliers, raid pressure, XP curves) logged to PostgreSQL for side-by-side cohort comparison |
| **Self-Hosted C11 Web & Persistence**            | `samplex` (`io_uring`), `samhttp`, `samhtml`, `libpq` multi-schema pattern (`dashboard.*`, `techtree.*`)                     | Powers the central server, multi-tab browser UI, bot API keys, and schema-isolated PostgreSQL persistence out of the box                                                                              |

And from a **"make content / show off on Fridays"** standpoint: watching 50
scripted and genetically evolved households compete to survive bog-iron
scarcity, specialize into mining/smithing castes, lose veteran knights in barrow
delves, and fortify keeps against escalating raids — while you tweak
world-economy levers like a mad demigod and chart the population curves — is
*inherently watchable* devlog/video material even before a single external human
player signs up.

______________________________________________________________________

## 2. Reading Index

This folder breaks the plan down into seven focused documents matching every
question in your brief:

1. **[`IronwoodWildsGameplay.md`](./IronwoodWildsGameplay.md)** — **Game &
   Gameplay Design Brief**\
   Premise & charter resets, the "Patient Sims" multi-tab household loop,
   OSRS-style character skill grind vs. Household Tech Tree permanence, Hold
   construction & pre-planned non-lethal Raid defense, Party PvM with permadeath
   stakes, Homeland Tribute recruitment economy, and population
   hypothesis-testing levers.

2. **[`01-ServerArchitectureAndPatterns.md`](./01-ServerArchitectureAndPatterns.md)**
   — **Server Architecture & C11 Design Patterns**\
   Bridging OOP patterns (Command, Strategy, Prototype/Flyweight, Observer) and
   Entity-Schema / Data-Oriented design into idiomatic arena-first C11. Full
   `sam*` library & app module split, memory lifecycle (`rules_arena` vs. pooled
   `world_arena` vs. `scratch_arena`), and `samplex` concurrency model.

3. **[`02-CommandsAndSimulationEngine.md`](./02-CommandsAndSimulationEngine.md)**
   — **Commands, Lazy Evaluation & Simulation Engine**\
   How commands travel from wire to world, two-phase validation (enqueue
   hard-fail vs. start fail-soft), why pure read-lazy breaks on shared nodes and
   how **Hybrid Lazy Timestamps + Causal Event Min-Heap** solves shared world
   state *and* unlocks 200× faster headless GA training, plus the future
   `samgenai` bridge.

4. **[`03-ProceduralGeneration.md`](./03-ProceduralGeneration.md)** —
   **Procedural World & Text Generation (`samprocgen`)**\
   Order-independent coordinate-hashed world layers (continuous geophysical
   fields → biomes → POIs/lairs → frontier spawn ring) paired with hierarchical
   slot/category/template text composition (`look` → `examine`) bound to live
   simulation wear, depletion, and weather.

5. **[`04-WebInterfaceAndProtocols.md`](./04-WebInterfaceAndProtocols.md)** —
   **Web Interface & Client Protocols**\
   The multi-tab "one tab per character + Hold tab" browser UX (`samhtml` + tiny
   vanilla JS timer island), plus how humans and bots share one command model
   across `samhttp` REST, cursor/SSE feeds, and a phase-2 `samplex` WebSocket /
   raw TCP bot socket adapter.

6. **[`05-AccountsAndPersistence.md`](./05-AccountsAndPersistence.md)** —
   **Accounts, Auth & PostgreSQL Schemas**\
   Three-schema PostgreSQL isolation (`auth.*` persistent across wipes,
   `wilds_meta.*` cross-season Hall of Fame & experiment configs, `wilds.*` live
   world state with sparse delta storage for procedural chunks), session cookies
   vs. bot `Bearer` API keys, write-behind checkpointing, and downtime-safe
   timer recovery.

7. **[`06-VerticalSlicePrototype.md`](./06-VerticalSlicePrototype.md)** — **The
   Vertical Slice Prototype ("The Timber & Bog-Iron Charter")**\
   What the smallest complete, bot-playable slice looks like, an end-to-end
   15-minute wire/simulation trace, and a Friday-by-Friday build sequence
   engineered so every single Friday ends with a runnable artifact.

______________________________________________________________________

## 3. Proposed `sam*` Banner & Ptah Layout

Ironwood Wilds is the **game/world brand** (domain, prose, web header).
Underneath, everything follows Ptah's naming and dependency rules:

```
libs/
├── samrena        (existing — foundation arenas & vectors)
├── samdata        (existing — SamHashMap, SamSet, SamHash, SamRng)
├── samstring      (existing — SamStringBuilder, SamStringList, SamTemplate)
├── samplex        (existing — io_uring async chains & worker pool)
├── samhttp        (existing — HTTP/1.1 router & per-connection arenas)
├── samhtml        (existing — escaped HTML tree & fragment rendering)
├── samvm          (existing — deterministic register VM)
├── samgenai       (existing — LGP evolution loop, plugged in later)
│
├── samprocgen     (NEW — seeded spatial field/POI math + YAML/table slot-based
│                         hierarchical text generator; zero game-specific rules)
├── samwilds       (NEW — pure, headless, deterministic simulation engine for
│                         Ironwood Wilds: entity pools, lazy/event scheduler,
│                         skills, tech tree, holds, raids, PvM, command dispatch)
└── samws          (NEW, Phase 2 — RFC 6455 WebSocket + line-socket framing on
                          samplex chains; optional — Slice 0 runs on samhttp)

apps/
├── samwilds-server (NEW — hexagonal online server: auth/wilds Postgres adapters,
│                          samhttp JSON API, samhtml multi-tab web UI, bot socket)
├── samwilds-cli    (NEW — curl/JSON terminal client for quick testing & admin)
└── samwilds-bot    (NEW — multi-household bot runner: scripted policies first,
                           hypothesis soak runner second, samgenai host third)
```

______________________________________________________________________

## 4. Key Architectural Decisions at a Glance

| #      | Area                 | Decision                                                                    | Why It Wins for Fridays + Emergence                                                                                                                                                                                 |
| ------ | -------------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **D1** | **Simulation Clock** | **Hybrid Lazy Timestamps + Causal Event Min-Heap** (`uint64_t` ms)          | Idle characters cost **0 CPU**. Shared resource nodes, PvM rounds, and Hold raids resolve in exact causal order. Headless bot/GA runs jump event-to-event at 1000× speed.                                           |
| **D2** | **Entity Model**     | **Relational Arena Pools ("Entity Schema in RAM") + Prototype Rule Tables** | Static `RuleDef` structs hold immutable stats (Prototype/Flyweight); dense generational-handle arrays hold mutable state. Maps 1:1 to PostgreSQL tables, hashes deterministically, and never calls `malloc`/`free`. |
| **D3** | **Extensibility**    | **Command Registry + Strategy Vtables**                                     | Every player/bot verb is a `SamWildsCommand` tagged union dispatched through a function-pointer table. Combat/Raid/Bot behaviors use Strategy structs (`fn ptrs + void *ctx`).                                      |
| **D4** | **ProcGen**          | **Coordinate-Hashed World + Sparse Delta Persistence**                      | `region(seed, x, y)` is pure math. Unvisited or untouched wilderness occupies **0 rows** in PostgreSQL; only surveyed metadata and depleted/built deltas are stored.                                                |
| **D5** | **Transport**        | **REST + Cursor Feed first (`samhttp`), `samplex` WS/Raw Socket second**    | `samhttp` works *today*. Because actions take minutes, local JS countdown timers + 2s cursor sync feel instant in Slice 0 without blocking on WebSocket framing.                                                    |
| **D6** | **Persistence**      | **3 PostgreSQL Schemas (`auth`, `wilds_meta`, `wilds`)**                    | Wiping the world for a new Charter/experiment is `DROP SCHEMA wilds CASCADE` + migrate — player accounts, bot API keys, and cross-season statistics stay untouched.                                                 |
| **D7** | **AI Seam**          | **Bots Are First-Class Clients (No Engine Cheating)**                       | Scripted bots (`samwilds-bot`) and future `samgenai` LGP controllers observe fogged household state and emit the exact same `SamWildsCommand` structs as browser tabs.                                              |
