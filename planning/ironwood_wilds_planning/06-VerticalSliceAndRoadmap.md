# 06 — Prototype Slice, Milestones (`st` Backlog), Review Gates & GA Bridge

> **Phase 0 reality note.** Phase 0 is complete (tickets `01`–`09`), and the code it produced uses neutral engine IDs, not the gameplay nouns this document was first written with. See the [Engine Primitives vs. Gameplay Nouns](README.md#engine-primitives-vs-gameplay-nouns) mapping table and [Current Status](README.md#current-status). Sections 1–2 (Slice 0 scope and the worked transcript) describe the **target** slice and keep the historical names (Household, Character, Site, Hold); read them through the mapping. Sections 3–4 show what exists today and what is planned. Neutral engine IDs are permanent. `OwnerId`, `ActorId` and `PlaceId` (generational `(index, generation)` records in `com.kotharworks.wilds.id`) are baked into the WAL, the wire format, `EventPayloadCodec` and SQL, and are never renamed. Gameplay concepts are layered on as state tables, `RuleRegistry` families and DTO fields keyed by those IDs. `CharacterId`, `HouseholdId`, `SiteId` and `HoldId` are historical names only.
>
> `libs/procgen` returns when the first real generator lands (planned: Phase 1 Ticket 14, `NameGrammar` + `PersonaGenerator`), absorbing `WorldSeed`/`SeededRng` from `com.kotharworks.wilds.seed`. Until then the `com.kotharworks.wilds.worldgen` seams (`WorldTopologyBootstrap`, `DescriptionComposer`) are the only procgen surface; the seed primitives live in `libs/wilds` only temporarily.

This document turns the architecture into an **actionable project-management & implementation plan**:
1. **Slice 0 (*"Hearth, Iron, and the Palisade"*):** Thin horizontal content, 100% permanent vertical spine.
2. **Worked Session Transcript:** Ending in bit-for-bit `wilds-headless replay --hash` parity.
3. **Java 25 Package Skeleton:** The packages that exist after Phase 0, plus the planned Phase 1 additions.
4. **Milestones `M0–M5` Execution Roadmap + `st` Ticket Backlog (`KOTHARWORKS`) + Post-Implementation Review Checklist.**
5. **Phase 2 Bridge (`SamVM` + `SamGenAI`):** How evolved genomes plug straight into `WildsEngine` and `Doctrine`.

---

## 1. Slice 0 Scope: *"Hearth, Iron, and the Palisade"*

Everything exotic (births/lineage, multi-biome roads, full 50-node tech tree) is deferred post-slice, while **every load-bearing seam ships cleanly once**:

| Seam Proven in Slice 0 | Included Scope | Deferred Post-Slice |
| :--- | :--- | :--- |
| **Household & Personas** | Start with **3 Settlers** generated via `NameGrammar` + `PersonaGenerator` (Origin, Temperament, Habit Quirk, First-Person Journal); zero idle autonomy (`IDLE_PATIENT`). | Births/lineage, roster > 6. |
| **Skills & Mini Tech Tree** | **5 Skills** (`Woodcutting`, `Mining`, `Carpentry`, `Smithing`, `Vanguard`, Lv. 1–50 XP curve) + **4 Tech Nodes** (`Charcoal Burning`, `Peat Bloomery`, `Timber Palisade`, `Signal Beacon`). | Remaining 5 skills, Tiers III–IV Stone/Ironwood keeps. |
| **Procedural World Ring** | Bounded `3×3` region neighborhood from `WorldSeed` (`RegionPassPipeline` + `WaypointGraph` + `headless gallery` ASCII map): Landing Clearing + Coppice + Peat Fen + Hematite Crag + Wolf/Barrow Lair + `glance`/`look`/`examine` prose. | Unbounded exploration, full 12-biome matrix, Myth climax raid. |
| **3-Ring Hold & Raid** | Build `Pit-Kiln`, `Bloomery`, `Vaulted Cellar` (`+40` protected stock), `Timber Palisade`. Foreshadowed **Greywood Marauder** rumor -> `WriteHoldDoctrine` -> deterministic **non-lethal** raid resolution (0 deaths; only unvaulted Bailey stock & wall condition at risk). | Multi-tier siege engines, regional awareness warmth. |
| **Lethal PvM Expedition** | Form `2–3` settler Party with `Frontline`/`Backline` + `CombatDoctrine` (Retreat HP%) -> delve *Gnarl-Root Barrow* -> turn-based Battle Chronicle, scars, **real permadeath**, and **Memorial Cairn** epitaph on death. | Multi-branch skill doors, poultice crafting chain. |
| **Tithe Ship & Recruit** | `StageShipment` at `Signal Beacon` -> scheduled **Homeland Tithe Ship** heartbeat converts crates to **Supply Favor** + emits public Chronicle line -> `RecruitSettler` brings Settler #4 ashore! | Dynamic Urgent War Requisitions. |
| **Server, Auth, UI, Persistence & Bot** | `libs/auth` (loopback-fused `dev-local` JWT -> drop-in Keycloak OIDC + scoped `iw_bot_...` keys), `WildsAuthorizer`, single-writer `WorldHost`, `POST /api/v1/commands` (`cmdId` idempotency), SSE `/api/v1/stream`, `/play` Multi-Tab UI (**"While You Were Away"** + Journal + Chronicle), 3-schema Postgres WAL + Snapshots, `apps/wilds-headless`, and `apps/wilds-bot` (`Doctrine` seam). | Public SVG Observatory charts (`M5b`), optional raw TCP `:8124`. |

---

## 2. Worked Slice 0 Session (How It Plays & Verifies)

*(In dev/demo mode `--time-scale=6` runs 3-minute actions in 30 real seconds; live Chronicles run `--time-scale=1`.)*

1. **`t = 0` — Landing & Personas:** You charter **House Ashford** at *Ravenbrook Clearing*. Three tabs appear:
   - **`[1] Mara Ashford`** (*Widowed Quarry-Mason · `SUPERSTITIOUS` · mistrusts barrow-stone · Mining Lv.3*)
   - **`[2] Pell Fen-Born`** (*Conscripted Peat-Cutter · `WRY` · notches tally-marks on tool hafts · Woodcutting Lv.3*)
   - **`[3] Cedric Vance`** (*Disgraced Watch-Sergeant · `STOIC` · wipes blades twice · Vanguard Lv.3*)  
   All three stand `IDLE_PATIENT`. You `POST /api/v1/commands` (`enqueue`) sending Mara to *Hook-Ridge Crag* (`TRAVEL -> GATHER hematite ×4 -> TRAVEL_HOLD -> DEPOSIT_ALL`), Pell to *Shelterwood Coppice* (`CHOP ash ×5`), and Cedric to research `Charcoal Burning`. You close your browser tab.
2. **`t + 35m` — Walk-Away Return & Fail-Soft Depletion:** You reopen `/play` on your phone.
   - The **"While You Were Away (35m · 8 events)"** card reports Pell hauled `10 Ash Timber` (+Lv.4 Woodcutting), while Mara's 4th hematite batch hit `ActionFailed(DEPLETED)`—because of fail-soft `ADVANCE`, she automatically walked home and deposited the 3 batches she already mined!
   - Mara's **Journal** reads: *"Day 1 — The red seam on Hook-Ridge bled dry before dusk. Older picks than ours already took the heart of this hill."*
3. **`t + 2h` — Bloomery, Tithe Ship & Raid Rumor:** With `Pit-Kiln`, `Bloomery`, `Vaulted Cellar`, and `Signal Beacon` built, you stage `20 Ash Timber + 4 Iron Spears`.
   - The **Homeland Tithe Ship** heartbeat fires -> `+95 Supply Favor`, and the public Chronicle wire broadcasts: *`SHIPMENT` · House Ashford shipped 20 timber and 4 spears; the Homeland breathes.*
   - Forging and shipping trip the raid threshold -> *`RUMOR` · Smoke seen past the eastern ridge — Greywood Marauders strike at dusk.*
   - On **`[H] Hold & Raid Plan`**, you finish `Timber Palisade` and set your **Defense Doctrine**: Cedric mans the Gatehouse; Mara and Pell retreat into the Keep Sanctuary. At impact, Cedric drops 2 raiders before falling back bruised (`HP 52/100`, **0 deaths**); raiders chip the palisade (`72/100`) and steal `4 unvaulted Timber` while all vaulted Iron Bars remain untouched.
4. **`t + 4h` — Lethal Barrow Delve, The Sting & Recovery:** Cedric, Mara, and Pell march on *Gnarl-Root Barrow*. When an alpha hound crits Mara past the retreat round, **Mara falls holding the threshold** (`ColonistDied`), while Cedric and Pell haul back *First-Age Iron Scrap*.
   - Mara's Journal seals into the Hold's **Memorial Cairn** with a generated epitaph; Cedric's Journal logs: *"Day 2 — Left Mara beneath a grey cairn above Gnarl-Root Barrow. Brought her hammer home to the Hall."*
   - You spend `60 Favor` (`RecruitSettler`) -> **Brenna Holt** (*Guild-Debt Apprentice · `EARNEST`*) steps off the next Tithe Ship into Tab 3.
5. **The Determinism Finish Line (Terminal):**
   ```bash
   mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-headless -am \
     -- replay --seed 0x49524F4E --wal chronicle0-wal.jsonl --to 14400000 --hash
   # WorldHash = 0x8f31c490a2d711be  (Matches live server checkpoint hash bit-for-bit!)
   ```

---

## 3. Monorepo Package Skeleton

### 3.1 What exists after Phase 0

```text
kotharworks/
├── libs/
│   ├── wilds/                               (Pure deterministic engine: zero I/O, zero floats, zero Auth)
│   │   └── com.kotharworks.wilds
│   │       ├── WildsEngine                  (Public facade)
│   │       ├── id/                          (OwnerId, ActorId, PlaceId, RuleId, EntityId, EntityIds)
│   │       ├── sim/                         (LazyGauge, ScheduledEvent, DueEventHeap, DuePayload, EntityTable)
│   │       ├── state/                       (WorldState, OwnerRow, ActorRow, PlaceRow, PlaceEdge, TaskSpec, ActiveTask, QueueFailurePolicy)
│   │       ├── rules/                       (RuleRegistry)
│   │       ├── command/                     (WorldCommand, Phase1Validator, Phase1Result, ErrorCode)
│   │       ├── task/                        (TaskMechanic, TaskQueueRunner, TaskContext, WaitMechanic, TravelMechanic)
│   │       ├── event/                       (WorldEvent, EventOutbox)
│   │       ├── observe/                     (ObservationProjector, WorldView, OwnerObservation, OwnerDto, ActorDto, PlaceDto)
│   │       ├── replay/                      (WorldHash, SnapshotCodec, EventPayloadCodec, CommandCodec, JsonCodec, WalHeader, WalRecord)
│   │       ├── seed/                        (WorldSeed, SeededRng; temporary home, see the procgen decision below)
│   │       └── worldgen/                    (WorldTopologyBootstrap, DescriptionComposer, MinimalSeedTopologyBootstrap, StubDescriptionComposer)
│   │
│   └── auth/                                (Game-agnostic AuthN & HTTP security; depends ONLY on nimbus-jose-jwt)
│       └── com.kotharworks.auth
│           ├── principal/                   (UserId, ExternalIdentity, GlobalRole, TokenId, sealed Principal)
│           ├── jwt/                         (JwtClaimsVerifier, JwtHumanAuthenticator, KeycloakRoleClaimExtractor)
│           ├── oidc/                        (OidcJwksAuthenticator, OidcAuthorizationCodeClient, OidcConfig; no live OIDC path yet)
│           ├── dev/                         (DevLocalJwtIssuer, DevPersona, LoopbackStartupFuse)
│           ├── token/                       (ApiTokenCodec, ScopedApiTokenAuthenticator)
│           ├── http/                        (AccessPolicy, AuthenticatedRouter, CookieCodec, CsrfGuard, RequestAuthenticator, SessionCookieAuthenticator)
│           └── spi/                         (IdentityProvisioner, ScopedTokenLookup, ScopedTokenRecord, SessionRecord, SessionStore)
│
├── apps/
│   ├── wilds-headless/                      (CLI determinism rig; no gallery subcommand yet)
│   │   └── com.kotharworks.wilds.headless
│   │       └── HeadlessMain, RunCommand, ReplayCommand, InspectCommand
│   │
│   └── wilds-server/                        (Hexagonal online host, Java 25 Virtual Threads)
│       └── com.kotharworks.wilds.server
│           ├── Main, ServerConfig, WildsServer
│           ├── domain/                      (WorldHost, SeatAuthorizer, SeatRateLimiter, CommandReceipt, CommandOutcome, ControllerKind, ...)
│           ├── ports/                       (WorldStorePort, AuthStorePort, EventBusPort, ClockPort, ...)
│           └── adapters/
│               ├── clock/                   (ClockPump)
│               ├── http/                    (AuthRoutes, CommandRoutes, QueryRoutes, SeatRoutes, ...)
│               ├── sse/                     (SseRoutes)
│               ├── web/                     (PlayRoutes, ChronicleRoutes, AssetRoutes, Html)
│               ├── memory/                  (InMemoryAuthStore, InMemoryWorldStore, InMemoryEventBus)
│               └── postgres/                (SqlMigrator, PostgresAuthStore, PostgresWorldStore)
│
└── systest/                                 (Opt-in `-Psystest` live suites, run by `./systest.sh`)
    └── com.kotharworks.systest
        └── bot/ headless/ human/ mixed/ regression/ restart/ security/ smoke/ support/
```

Not present yet: `libs/procgen`, `apps/wilds-bot`, `deploy/` (no Keycloak compose).

### 3.2 Planned Phase 1 additions (not built)

- **Decision (procgen).** `libs/procgen` returns when the first real generator lands (planned: Phase 1 Ticket 14, `NameGrammar` + `PersonaGenerator`), absorbing `WorldSeed`/`SeededRng` from `com.kotharworks.wilds.seed`. Until then the `com.kotharworks.wilds.worldgen` seams (`WorldTopologyBootstrap`, `DescriptionComposer`) are the only procgen surface; the seed primitives live in `libs/wilds` only temporarily.
- **Decision (IDs).** Neutral engine IDs are permanent. `OwnerId`, `ActorId` and `PlaceId` (generational `(index, generation)` records in `com.kotharworks.wilds.id`) are baked into the WAL, the wire format, `EventPayloadCodec` and SQL, and are never renamed. Gameplay concepts are layered on as state tables, `RuleRegistry` families and DTO fields keyed by those IDs. `CharacterId`, `HouseholdId`, `SiteId` and `HoldId` are historical names only.
- `libs/procgen`: `persona/` (`NameGrammar`, `PersonaGenerator`, `JournalComposer`; Ticket 14), `geography/` (Ticket 15), `prose/` (Ticket 16), `delves/` (`LairGraphGenerator`; Ticket 22).
- `libs/wilds`: `economy/` (Ticket 20), `combat/` (Tickets 21–23), plus item/stockpile state tables keyed by `OwnerId`/`ActorId` (Tickets 12–13).
- `apps/wilds-bot`: pure HTTP+SSE client with the `Doctrine` seam (Ticket 24); never links `libs/wilds`.

---

## 4. Milestones (`M0–M5`) & `st` Ticket Backlog

![Milestone Execution Roadmap DAG](diagrams/07-milestone-roadmap.svg)

> *Graphviz artifacts:* [`07-milestone-roadmap.dot`](diagrams/07-milestone-roadmap.dot) · [`SVG`](diagrams/07-milestone-roadmap.svg) · [`PNG`](diagrams/07-milestone-roadmap.png)

### 4.1 Milestone Breakdown

> Execution order so far was M0 -> M4 -> (M1–M3): the server spine landed before gameplay. Scope text below keeps the original gameplay nouns; read it through the [mapping table](README.md#engine-primitives-vs-gameplay-nouns) and see [Current Status](README.md#current-status).

| Milestone | Status | Scope | Mechanically Verified Acceptance Gate |
| :---: | :---: | :--- | :--- |
| **M0: Deterministic Kernel & Headless Rig** | **Done** (tickets `01`–`05`) | Generational IDs, `EntityTable`, integer `LazyGauge`, `DueEventHeap` (+ `heapTicket` cancel), `WorldCommand` switch, `WorldHash`, reflection purity/no-float arch test, `apps/wilds-headless` scaffold. | Equal-`dueAtMs` tiebreak test; purity/no-float reflection test green; double-run & snapshot-roundtrip `WorldHash` equality; `-DblessGolden=true` harness works. |
| **M1: Seeded World, Personas & Prose (`M1a–M1b`)** | Not started | **M1a:** `SeededRng`, `OpenSimplex2S`, `PoissonDisc`, `RegionPassPipeline` + `WaypointGraph`, `MythGenerator`, `headless gallery --seed`.<br>**M1b:** `NameGrammar`, `PersonaGenerator`, `JournalComposer`, `ProseComposer` (`glance`/`look`/`examine`), plus `Travel`/`Gather`/`Deposit` with fail-soft queues. | `headless gallery` prints coherent ASCII map; 0-apostrophe name tests; scripted story test: Mara & Pell gather, hit `DEPLETED`, fail-soft walk home, and emit distinct temperament-voiced Journal lines! |
| **M2: Skills, Tech, Hold & Tithe Economy** | Not started | 5 Skills + XP curve, `RuleRegistry` (`WILDS_RULES_VERSION`), `Craft`/`Construct`/`Research`, `Signal Beacon` staging, `TitheShipSystem` heartbeat -> Favor -> `RecruitSettler` (mints new `CharacterPersona`). | 10k-command randomized invariant fuzz (no negative items, conservation holds); headless story test: bare camp -> smelt iron -> ship crate -> recruit 4th settler. |
| **M3: Plannable Violence (Raids & PvM)** | Not started | 3-Ring Hold math (`Vault` protected floor), foreshadowed Raid rumor + non-lethal `RaidResolver`, Party lazy muster + lethal `ExpeditionResolver`, scars, Memorial Cairn epitaphs. | Combat golden test; hard property test proving **0 defender deaths across 1,000 Hold Raids** and `0` vaulted item losses; lethal PvM test verifying Cairn epitaph & survivor grief mark. |
| **M4: Modular Auth, Online Server, UI & Postgres (`M4a–M4c`)** | **M4a** Done (`06`, `07`)<br>**M4b** Partially done (`09`): minimal SSR `/play` console (seat claim and multi-seat switcher, Wait/Travel queueing) and `/chronicle`; no multi-tab deck, persona/journal or "While You Were Away" digest yet<br>**M4c** Partially done (`08`): Postgres + crash recovery done; Keycloak `deploy/` compose and live OIDC path not done | **M4a:** `libs/auth` (`Principal`, `JwtClaimsVerifier`, loopback-fused `DevLocalJwtIssuer`, `iw_bot_...` keys, `AuthenticatedRouter` + `CsrfGuard`, `AuthSecurityGateTest`) + `SeatAuthorizer` + `WorldHost` mailbox (Commit-Before-Mutate + `CLOCK_ADVANCE`) + REST `/api/v1/*` + `POST /api/v1/commands` (`cmdId` + `expectedRevision`) + SSE `/api/v1/stream` (in-memory stores).<br>**M4b:** SSR `/play` Multi-Tab UI (`1–9`/`H`/`E`), `expectedRevision` ETag forms, **"While You Were Away"** digest, Persona/Journal cards, 60fps local timers.<br>**M4c:** Keycloak OIDC (`OidcJwksAuthenticator` + PKCE + `deploy/keycloak/compose.yaml`), `SqlMigrator` + 3-schema Postgres (`auth`, `wilds_meta`, 100% append-only `wilds_world` — zero passwords, zero entity-sync `UPDATE`s!), `kill -9` WAL+Snapshot recovery. | The `AuthSecurityGateTest` suites green; duplicate `cmdId` returns cached receipt; stale `expectedRevision` returns `409 STALE_STATE`; simulated `SQLException` on `command_wal` insert leaves `WorldState` untouched; passive SSE events followed by `kill -9` + restart experience **0 cursor rewind** and match `wilds-headless replay` `WorldHash`. |
| **M5: Reference Bot, Observatory & Launch (`M5a–M5d`)** | Not started (`M5a`–`M5d`) | **M5a:** `apps/wilds-bot` (`GreedyFrontierDoctrine` over HTTP+SSE only) + 10-bot CI soak test.<br>**M5b:** Public `/chronicle` Observatory (SVG Frontier Map + `HUMAN` vs `SCRIPT_BOT` telemetry).<br>**M5c:** Tier III/IV Stone & True Ironwood content pass + Season Archive CLI.<br>**M5d:** VPS deploy & **Chronicle I** live! | `wilds-bot` plays 3 simulated days unattended at `20×` speed (achieves bloomery + palisade + shipment + recruit, 0 raid deaths); live session WAL replays in `wilds-headless` to identical `WorldHash`. |

### 4.2 Ticket Backlog (`--project KOTHARWORKS`)
The original 19-item decomposition is superseded. The authoritative, current index (build order, dependency graph, ticket template and Definition of Done) is [`tickets/README.md`](../../tickets/README.md); the closed Phase 0 index is [`tickets/done/PHASE0-README.md`](../../tickets/done/PHASE0-README.md). Mapping to milestones:

| # | Ticket | Milestone | Status |
| :---: | :--- | :---: | :---: |
| 01 | [Workspace cleanup, `WorldSeed`/`SeededRng`, bytecode purity gate](../../tickets/done/01-workspace-cleanup-and-determinism-gates.md) | M0 (also seeds M1 prep) | Done |
| 02 | [Generational IDs, `EntityTable`, `LazyGauge`, `DueEventHeap`](../../tickets/done/02-generational-tables-gauges-and-event-heap.md) | M0 | Done |
| 03 | [`WorldState`, `RuleRegistry`, procgen seams, `JsonCodec`, `WorldHash`](../../tickets/done/03-neutral-world-state-rules-and-procgen-seams.md) | M0 | Done |
| 04 | [Commands, Wait/Travel task queues, `EventOutbox`, `ObservationProjector`, golden replay](../../tickets/done/04-commands-task-queues-events-and-projections.md) | M0 | Done |
| 05 | [Headless CLI + WAL replay](../../tickets/done/05-headless-cli-and-wal-replay-rig.md) | M0 | Done |
| 06 | [`libs/auth` + `AuthSecurityGateTest`](../../tickets/done/06-modular-auth-and-http-security-library.md) | M4a | Done |
| 07 | [`WorldHost`, `SeatAuthorizer`, REST + SSE in-memory](../../tickets/done/07-server-worldhost-rest-and-sse-inmemory.md) | M4a | Done |
| 08 | [Postgres migrator, JDBI, crash recovery](../../tickets/done/08-postgres-schemas-migrator-and-jdbi-adapters.md) | M4c | Done |
| 09 | [Minimal SSR `/play` + `/chronicle` console and full-spine E2E](../../tickets/done/09-minimal-ssr-console-and-end-to-end-system-tests.md) | M4b (partial) + E2E | Done |
| 10 | Planning reconciliation & Phase 1 index (`tickets/done/10-planning-reconciliation-and-phase1-index.md`) | — | Done |
| 11 | [Rules-version gate: `RulesFingerprint` pin, clean boot refusal & world rollover](../../tickets/11-rules-version-gate-and-world-rollover.md) | M2 prep | Planned |
| 12 | Items, stockpiles, carry & `Deposit` | M1b | Planned |
| 13 | Resource nodes, `Gather` ×N & fail-soft `DEPLETED` | M1b | Planned |
| 14 | Restore `libs/procgen`: `NameGrammar`, `PersonaGenerator`, 3-settler charter | M1b | Planned |
| 15 | Bounded 3×3 region geography, `WaypointGraph` & `headless gallery` | M1a | Planned |
| 16 | `ProseComposer` (`glance`/`look`/`examine`) & `JournalComposer` | M1b | Planned |
| 17 | `/play` multi-tab Character Deck (persona, journal, stockpile, queue builder) | M4b | Planned |
| 18 | Skills, XP curve & stamina modifiers | M2 | Planned |
| 19 | Craft, Research & Construct (4 techs, 5 buildings) | M2 | Planned |
| 20 | Tithe Ship heartbeat, Favor & `RecruitSettler` | M2 gate | Planned |
| 21 | Hold rings, raid rumor & non-lethal `RaidResolver` | M3 | Planned |
| 22 | Parties, `LairGraphGenerator`, lazy muster & `CombatDoctrine` | M3 | Planned |
| 23 | `ExpeditionResolver`, permadeath, scars & Memorial Cairn | M3 gate | Planned |
| 24 | `apps/wilds-bot` `GreedyFrontierDoctrine` & 3-day soak | M5a gate | Planned |
| infra | Keycloak `deploy/` compose + live OIDC `systest` path | M4c | Planned |

### 4.3 Post-Implementation Code Review Checklist (Our Gate on Every Ticket)
Every ticket completed by the coding agent is reviewed against seven non-negotiable checks before closing. The per-ticket gate in [`tickets/README.md`](../../tickets/README.md) (its Definition of Done) is authoritative; this list is the planning-level summary.
1. **`mvnd test` passes with zero warnings (`-Werror`) and `./systest.sh` passes** (the one-command definition-of-done gate: Maven suite plus the live bot/human `systest/` suites in Docker), and every new `.java` / `pom.xml` file carries the **Apache 2.0 Samuel "Lord-Windy" Brown header**.
2. **Purity & Zero-Float Check:** Zero `float`/`double`, `HashMap` iteration, wall-clock calls, `libs/auth` imports, or I/O inside `libs/procgen` (once the module returns) or `libs/wilds` state/mutation paths.
3. **No Cross-Entity Object Pointers:** Entities hold only generational value IDs (`ActorId`, `OwnerId`, `PlaceId`).
4. **Single-Writer & Commit-Before-Mutate Discipline (Server/DB Tickets):** Pure read-only Phase-1 validation (including `expectedRevision` -> `409 STALE_STATE`) runs inside `WorldHost`; `command_wal` (`WorldCommand` and `CLOCK_ADVANCE`) commits synchronously **before** `WorldState` mutates or SSE emits; `wilds_world` contains zero mutable entity-sync `UPDATE` tables.
5. **Fog Security:** Client responses come exclusively through `ObservationProjector` DTOs, never serializing internal `*Row` records.
6. **Determinism Proof:** Any rule/mechanic change updates or adds a deterministic story/golden hash test and bumps `WILDS_RULES_VERSION` if intentional. Once Ticket [`11`](../../tickets/11-rules-version-gate-and-world-rollover.md) lands, the pin is mechanical ("…bumps `WILDS_RULES_VERSION`; `RulesVersionPinTest` enforces this"); until then the test does not exist and this is a review-time check.
7. **AuthN/AuthZ & Crypto Safety (Auth/Server Tickets):** Zero password storage or hand-rolled JWT crypto (`nimbus-jose-jwt` asymmetric allowlist only); every route declares an explicit `AccessPolicy`; `dev-local` aborts boot on non-loopback addresses; the `AuthSecurityGateTest` negative checks stay green.

---

## 5. Phase 2 Bridge: Plugging in `SamVM` & `SamGenAI`

Once Slice 0 + `apps/wilds-bot` stand, evolving Genetic Algorithm populations requires **zero architectural rewrites**:

1. **Port `libs/samvm` & `libs/samgenai`:** Fixed 8-byte register-VM instructions with deterministic integer/fixed-point registers and instruction budgets.
2. **Headless Evolution at 10,000× Real Time (`apps/wilds-arena`):**  
   Spawn 200 independent `WildsEngine` trials in parallel across Java 25 Virtual Threads, jumping straight from event to event via `engine.advanceToNextEvent()` (~2–5 ms per simulated week!) and scoring genomes by `(Supply Favor + Hold Tier + Surviving Skill XP)`.
3. **Live Deployment via `Doctrine`:**  
   Wrap any champion `.samvm` genome inside `apps/wilds-bot`'s `Doctrine` interface, charter a House with `controller_kind = 'GENETIC_AGENT'`, mint a House-scoped `iw_bot_...` token in `auth.api_tokens`, and watch it settle a Hold, write Raid Doctrines, and ship Tithe Crates live alongside human players on `/chronicle`!
