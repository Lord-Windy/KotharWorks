# Ironwood Wilds — Implementation Roadmap and Future AI

## 1. How to use this plan

Work in dependency-ordered, demonstrable milestones. Do not wait for a perfect full specification, but do specify a mechanic before implementing it. Keep the protocol, state machines, and record formats stable enough that each milestone adds rather than replaces the previous one.

The milestones are not calendar promises. If a milestone cannot be demonstrated at the end of a Friday, reduce its content rather than widening its architecture. Keep a short decision log for changes to gameplay, persistence, and command semantics.

## 2. Milestone dependency graph

```text
M0 Specification
  └─> M1 Core action loop
        └─> M2 Durable/API walking skeleton
              ├─> M3 Generated geography
              │     └─> M4 Economy and browser household
              │           └─> M5 PvM and holding raids
              │                 └─> M6 Bot/replay/hardening -> vertical slice review
              └─> (basic HTML shell may proceed alongside M3/M4)

After review:
  M7 Living world and content depth
  M8 Showcase/spectator tools
  M9 Isolated genetic-controller experiments
```

M2 deliberately uses simple full snapshots aggressively if necessary. Optimisation and broad read projections wait for actual measurements.

## 3. Milestones

### M0 — Normative design baseline

**Build no game systems yet.** Turn the accepted planning documents into a small normative spec suite:

- world/account/household/character state definitions;
- logical-time, agenda ordering, downtime, and queue rules;
- command and event envelopes with version policy;
- action lifecycle and resource/cancellation semantics;
- first content definitions and the vertical slice's normative numeric table: starting stock/XP, every action duration/yield/recipe/cost, skill formula/thresholds, shipment/Favor/recruitment values, carried/camp capacities, combat and raid formulas, first/subsequent raid times, grace, rest/repair, and zero-Favor emergency charter (use `IronwoodWildsVerticalSlice.md`'s proposed baseline unless deliberately edited);
- persistence ownership and migration policy;
- threat model: auth, household privacy, CSRF, token scope, rate limits;
- determinism/state-hash definition.

**Demo/exit:** two independent examples—mine/ship and expedition/raid—can be walked through the documents without an ambiguous order of operations.

### M1 — Headless core action loop

Use a tiny fixed test map. Implement only:

- world/entity IDs and arena lifetimes;
- immutable rules registry;
- commands and domain effects;
- agenda and `advance_to(T)`;
- character queues and action handlers for move/gather/craft;
- structured observations;
- canonical state serialization/hash and deterministic replay test.

**Demo/exit:** a C test directs three characters through a small plan, fast-forwards directly between deadlines, reloads, and obtains the same state hash. No HTTP or database is required.

### M2 — Durable and networked walking skeleton

Add:

- PostgreSQL identity/control-plane/snapshot/event/command tables;
- account/session/token adapters using vetted password hashing, CSPRNG-generated high-entropy secrets, rotation/revocation, account-disable revocation, and reverse-proxy per-IP limits;
- world repository and atomic commit boundary, including in-writer idempotency and unknown-commit reconciliation;
- one world runtime plus an explicitly built earliest-deadline timer adapter over samplex's low-level timeout operation: monotonic anchoring, earlier-deadline re-arming, stale-wake rejection, admission-versus-clock pause, and restart/resume anchoring tests;
- `samwilds-server` composition root;
- minimal REST observations/commands/events;
- no-engine-link CLI;
- crash/retry/ownership integration tests.

Initially direct core observations can replace `iw_read`; snapshots may be frequent because the state is tiny. Avoid a general ORM or elaborate web UI.

**Demo/exit:** a CLI user registers/logs in, controls a character over HTTP, kills/restarts the server mid-action, retries a command, and continues the same world safely.

### M3 — Seeded geography

Add:

- region/tile/site model;
- deterministic coordinate-based streams;
- Level A generator and bounded start validation/repair;
- travel costs/pathfinding;
- resource depletion and simple scheduled recovery;
- generated base plus mutable delta persistence;
- generator property tests and ASCII inspection output.

**Demo/exit:** 100+ seeds satisfy start invariants; shuffled materialisation order gives identical hashes; the CLI shows a seeded map and directs travel across it.

### M4 — Household economy and browser UI

Add:

- inventory/capacity/reservations;
- skills and technology;
- construction;
- shipments, Home Favor, recruitment;
- `samhtml` dashboard, character tabs, map/site pages, tech/holding/shipment pages;
- native forms with progressive vanilla-JS event updates;
- event paging/resync and multi-tab revision handling.

**Demo/exit:** a fresh household completes the gather → research → craft → build → ship → recruit loop in a browser, and two households cannot access one another's private state.

**Important gate:** play this loop before adding more content. If it is tedious rather than interesting, change queue, duration, resource, or goal design now.

### M5 — PvM and holding raids

Add:

- party/expedition state machine and member reservations;
- formation/tactics, deterministic combat rounds, injuries/death/loot;
- recruitment recovery/emergency charter;
- scheduled raid threat, standing defence plan, nonlethal raid defeat;
- combat/raid chronicle rendering.

**Demo/exit:** deterministic fixtures demonstrate victory, death/replacement, defended raid, and unprotected raid loss; a raid resolves correctly while its owner is offline.

### M6 — External bot, replay, and release hardening

Add:

- deterministic scripted `samwilds-bot` over HTTP only;
- accelerated test configuration without changing rules semantics;
- real-socket/API/browser tests;
- crash-before/after-commit and stale-agenda tests;
- token revocation, CSRF, rate limiting, request limits;
- backup/restore, world close/new-season isolation;
- decision logs and curated demo seeds/records.

**Demo/exit:** every criterion in `IronwoodWildsVerticalSlice.md` passes, the bot can play unaided, and a fresh user can understand the first-session dashboard.

Only now call it a vertical slice worth showing.

## 4. M7 — Deepen the world based on observed play

Choose one or two tracks at a time, not all simultaneously.

### Environment track

- drainage, soil, and richer biome conditions;
- weather fronts and seasonal resource effects;
- forest cohorts, regrowth/succession, and harvesting pressure;
- prey/predator populations and monster migration;
- coarse scheduled ecology updates with catch-up bounds.

### Household track

- needs/rest only if they create good planning decisions;
- equipment quality/durability and recovery;
- richer injuries and character histories;
- construction plots, roads, storage logistics;
- trade/cooperation only after ownership and economy implications are designed.

### Progression track

- larger but meaningful tech branches;
- alternative production chains and biome-specific resources;
- holding tiers from camp to keep;
- new monster behaviors and expedition objectives;
- seasonal rule modifiers that change strategies rather than only numbers.

### Social track

- public contracts or cooperative shipments;
- consensual trade and shared landmarks;
- household co-op permissions;
- no PvP remains an invariant.

Every addition needs observable causality, content validation, replay/save support, and bot/protocol treatment. Otherwise it becomes a demo-only special case.

## 5. M8 — Make the world watchable and shareable

The project motivation includes showing the simulation, so invest in presentation **after** the core loop works:

- read-only spectator observations with explicit public/household-safe visibility;
- chronicle filters by character, site, expedition, technology, shipment, and raid;
- event-record export and a scrubber that renders historical observations;
- ASCII map time-lapse generated from committed events, not a second simulation;
- household/region summaries: resources extracted, characters lost, technologies discovered, shipments completed;
- seed/map inspector and deterministic replay verifier in `samwilds-lab`;
- embeddable/read-only highlight reports or static HTML exports.

A good showcase should let a viewer answer “why did this happen?” rather than only watch timers change. Do not expose another household's private live plan for entertainment without an explicit public/spectator rule.

## 6. M9 — Genetic algorithms, only after the baseline works

### 6.1 Prerequisites

- the ordinary game loop is playable and externally bot-driven;
- isolated headless worlds support accelerated logical time;
- observations and commands are versioned;
- records/replays and reward-relevant events are reliable;
- a scripted baseline supplies meaningful comparison metrics;
- `samgenai`'s real selection/mutation/crossover work exists. Its current genetics are synthetic scaffolding, so merely linking it would not provide learning.

### 6.2 Training topology

```text
samwilds-lab / training runner
  ├── isolated world per episode (own arena, virtual clock, no production DB)
  ├── public-shaped observation encoder
  ├── legal command generator/mask
  ├── genome/controller evaluation via samgenai
  ├── event-based fitness and completion predicates
  └── seed, genome, ruleset, observation, action-schema, and fitness versioning
```

Do not require GA fitness evaluation to use HTTP or production PostgreSQL. Use the equivalent in-process command/observation boundary. Conversely, a deployed learned controller that plays the live server should use the ordinary bot API like everyone else.

### 6.3 Start with a narrow problem

Do not initially ask a genome to understand the entire tech tree. A reasonable first task is:

> Given three characters, a fixed small map, and a limited action vocabulary, produce a shipment while keeping all characters alive within a logical-time budget.

Then expand to recruitment, a safe hunt, or raid preparation.

### 6.4 Observation and action encoding

- Keep observation fields explicit, normalised, and versioned.
- Include prior action/outcome features if temporal memory is needed; current samvm calculation registers do not implicitly persist between invocations.
- Select from legal parameterized commands rather than allowing arbitrary numeric output to become entity IDs or memory writes.
- A small grammar/hierarchical policy can choose `{action, character, target, count}` while the server validates as usual.
- Reset must recreate all world, agenda, RNG, and controller-observation state between episodes.

### 6.5 Fitness and degenerate behavior

Begin with transparent event-based terms:

- shipment arrived / Favor earned;
- technology/building milestone;
- characters alive and injuries;
- expedition/raid objective;
- logical time and command/action failure penalties.

Watch for reward hacking: farming failure XP, queue churn, suicidal high-variance strategies, idling forever to avoid loss, or exploiting a test-only rule. Compare against the scripted baseline over many fixed seed cohorts, not one successful replay.

### 6.6 Separation from live state

Training workers never write live schemas, never call the live world writer directly, and never share a mutable world handle. Promote a trained genome into an ordinary external bot artifact only after replay and cohort evaluation. The live server does not link samgenai.

## 7. Testing and quality gates that continue forever

### Every milestone

- deterministic core and relevant goldens;
- action/queue/agenda invariants;
- positive and negative protocol cases;
- versioned save/event compatibility policy;
- formatting, CTest, and Valgrind as appropriate to the touched code;
- content definitions validated against registered handlers and other definitions.

### Before public access

- real TLS deployment and secure cookie/token behavior;
- disposable-database migration/restore rehearsal;
- rate limits and payload bounds;
- no secrets in logs, URLs, chronicle, or errors;
- backup restoration and world closure drill;
- measured HTTP stalls from synchronous persistence/generation;
- explicit behavior on overload: queue/backpressure/503 rather than unbounded work.

### Before deeper simulation

- profiling identifies where time/memory actually goes;
- catch-up/work budgets are tested;
- new systems have spectator and bot observations;
- the existing vertical slice remains playable.

## 8. Risk register

| Risk | Early signal | Mitigation |
|---|---|---|
| Simulation scope consumes all time | Many systems, no complete loop | Keep the First Charter exclusions; require a demo each milestone |
| Lazy evaluation creates order bugs | Polling/restart changes outcomes | One writer, logical admission records, stable agenda keys, polling-invariance tests |
| Persistence becomes its own project | ORM/projection work before gameplay | Whole snapshots plus effects; minimal schemas; direct observations first |
| samhttp stalls under load | DB/generation bursts block all routes | Small transactions, metrics, bounded work; later async bridge or world-owner process |
| Retry duplicates valuable effects | Double recruits/shipments after timeout | Command IDs, stored results, atomic commit, explicit retry tests |
| Procedural realism becomes a rabbit hole | Erosion code but no viable start | Level A generator, hard start invariants, repair/fallback, deeper stages later |
| Multi-tab UI causes stale overwrites | One tab silently replaces another's queue | Expected revisions, events, conflict UI, idempotent forms |
| Grind is empty rather than interesting | Optimal play is repetitive queue maintenance | Playtest M4; add interacting constraints and goals before more tiers |
| Bots need privileged APIs | Bot links core or reads hidden state | No-engine-link client rule and audience-filtered observations from the start |
| GA work starts too early | Fitness code before a playable environment | Scripted bot baseline and vertical-slice review gate |
| GA never learns anything useful | Synthetic progress or one lucky seed | Real genetics prerequisite, fixed cohorts, baselines, versioned rewards |
| Reset mixes old/new world state | Stale request modifies new season | New world ID everywhere, close admissions, archive before reopening |

## 9. Practical repository habits once implementation begins

- Register new components in `PROJECTS.md` and keep the umbrella-header/arena/test conventions in `AGENTS.md`.
- Keep content spec and actual implementation visibly in sync; do not leave speculative mechanics presented as shipped behavior.
- Preserve old record readers where compatibility is promised; never bless new goldens merely to hide an unexplained hash change.
- Test bots over real sockets and core determinism separately; one does not prove the other.
- Do not run destructive database tests outside disposable fixtures.
- Commit/push and close samtasker tickets only when explicitly working under that workflow; this planning pack intentionally creates no tickets.

## 10. Recommended immediate next step

Do not implement all eight documents. First review and edit the decisions in this order:

1. `IronwoodWildsGameplay.md` — is the patient multi-character loop the game you actually want?
2. `IronwoodWildsVerticalSlice.md` — remove anything not needed to test that loop.
3. `IronwoodWildsSimulation.md` — agree on queue/cost/cancellation and same-time semantics.
4. `IronwoodWildsPersistence.md` — agree on snapshot/effect recovery and account/world lifetime.
5. Only then turn M0/M1 into implementation tickets.

That keeps the first code focused on the central bet: **a small world of patient, individually directed people can produce stories worth watching.**
