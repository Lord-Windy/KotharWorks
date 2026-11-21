# KotharWorks — Phase 0 Base Structure Tickets ("The Bare Engine Spine")

This folder tracks the initial ticket set to stand up the **gameplay-neutral engine and server spine** for **Ironwood Wilds** inside `kotharworks`.

When a ticket is implemented, verified (`mvnd test` passing with zero warnings), and committed, move its markdown file from `tickets/` into [`tickets/done/`](done/).

---

## 1. Guiding Philosophy ("The Stock DikuMUD Principle")

Think of this phase like downloading a clean MUD codebase (`DikuMUD` / `CircleMUD`) with only its 3-room `limbo` test zone attached: **the engine, loop, networking, auth, persistence, console, and replay rig all run end-to-end, while the actual game rules have not been written yet.**

1. **Zero Premature Gameplay Lock-In:**
   - Core state uses neutral structural primitives: **`Owner`** (`OwnerId`), **`Actor`** (`ActorId`), **`Place`** (`PlaceId`), **`RuleId`**, **`LazyGauge`**, and **`TaskSpec`** (with only two stock structural tasks: `Wait` and `Travel`).
   - **Explicitly excluded from this phase:** Holds, 3-Ring fortifications, Raids, PvM Expeditions, Parties, Skills/XP curves, Tech Trees, Items/Inventories, Tithe Ships, and Character Personas/Journals. Gameplay will evolve cleanly on top of these tables, command switches, strategy seams, and forward SQL migrations once finalized.
2. **Procgen Considered at the Seams — Zero `libs/procgen` Module:**
   - The scaffold `libs/procgen` module is **removed** in Ticket `01`.
   - `libs/wilds` defines `WorldSeed` + domain-separated integer `SeededRng` (`com.kotharworks.wilds.seed`) and explicit strategy interfaces (`WorldTopologyBootstrap` and `DescriptionComposer` in `com.kotharworks.wilds.worldgen`) backed by minimal deterministic 3-place stubs. This proves *where and how* procedural generation is called (world/sector bootstrap, lazy place materialization, and read-time `glance`/`look`/`examine` rendering) without prematurely building a procgen library.
3. **Bots Considered at the Contract Boundary — Zero Bot Implementation:**
   - `apps/wilds-bot` is **not** created in this phase.
   - Bot compatibility is preserved structurally by having **one canonical mutation endpoint** (`POST /api/v1/commands` with `cmdId` idempotency + `expectedRevision` optimistic concurrency), self-describing `GET /api/v1/rules`, SSE `/api/v1/stream`, and `Principal.ScopedTokenPrincipal` (`iw_bot_...`) + `controller_kind` support in `libs/auth` and `wilds_meta.seats`.
4. **Production-Grade Architectural Invariants From Day 1:**
   - **100% Pure & Deterministic `libs/wilds`:** Zero runtime dependencies, zero I/O, zero wall-clock calls, zero `float`/`double` in `WorldState`, zero `HashMap`/`HashSet` state iteration, enforced mechanically via JDK 25 `java.lang.classfile` bytecode scanning (`WildsArchitectureGateTest`).
   - **Hybrid Lazy Simulation:** Integer milli-unit `LazyGauge` + `(dueAtMs, seq)` `DueEventHeap` + O(1) `heapTicket` cancellation.
   - **Single-Writer `WorldHost` + Lock-Free `WorldView` MVCC + Commit-Before-Mutate WAL:** Pure read-only Phase-1 validation (`409 STALE_STATE` on `expectedRevision` mismatch) runs inside `WorldHost`; Postgres `command_wal` (`WorldCommand` and `CLOCK_ADVANCE`) commits *before* `WorldState` mutates or SSE emits.
   - **Modular Zero-Password Auth (`libs/auth`):** Nimbus JOSE+JWT asymmetric verification, loopback-fused `dev-local` JWT persona switcher (`127.0.0.1` only) ready to flip to Keycloak `--auth-mode=oidc`, BFF session cookie + `CsrfGuard`, enforced by `AuthSecurityGateTest`.

---

## 2. Target Module Topology After Ticket 09

```text
kotharworks/  (com.kotharworks:kotharworks-parent · Pure Java 25 LTS · -Werror)
├── libs/
│   ├── wilds/               0 external runtime deps · Pure deterministic engine, state, queues, codecs & WorldHash
│   └── auth/                Reusable AuthN & HTTP security (`nimbus-jose-jwt` only · injectable `java.time.Clock`)
└── apps/
    ├── wilds-headless/      CLI rig (`run`, `replay --hash`, `inspect`) — depends ONLY on `libs/wilds`
    └── wilds-server/        Hexagonal host: Virtual-Thread HTTP/SSE + `WorldHost` + SSR `/play` console + Postgres
```

---

## 3. Build Order & Dependency Graph

```text
  [01 Workspace Cleanup, WorldSeed/SeededRng & JDK 25 Bytecode Gate]
                 │                                       │
                 ▼                                       ▼
  [02 Generational IDs, EntityTable,            [06 Modular libs/auth +
      LazyGauge & DueEventHeap]                     AuthSecurityGateTest]
                 │                                       │
                 ▼                                       │
  [03 Neutral WorldState, RuleRegistry,                  │
      Procgen Seams, JsonCodec & WorldHash]              │
                 │                                       │
                 ▼                                       │
  [04 Commands, Two-Phase Task Queues,                   │
      Events, Fog Projector & Golden Harness]            │
          │                      │                       │
          ▼                      └───────────┬───────────┘
  [05 apps/wilds-headless                    ▼
      CLI & WAL Replay Rig]     [07 apps/wilds-server Core: WorldHost,
          │                         SeatAuthorizer, REST + SSE (InMemory)]
          │                                  │
          │                                  ▼
          │                     [08 PostgreSQL 3-Schema Migrator, JDBI
          │                         Adapters & Crash/Pause Recovery]
          │                                  │
          └──────────────┬───────────────────┘
                         ▼
          [09 Minimal SSR `/play` Console &
              Full-Stack End-to-End Integration Suite]
```

| Order | `st` Key | Ticket File | Module(s) | Primary Deliverable |
| :---: | :---: | :--- | :--- | :--- |
| **1** | `4e263046` | [`01-workspace-cleanup-and-determinism-gates.md`](01-workspace-cleanup-and-determinism-gates.md) | root, `libs/wilds` | Remove `libs/procgen`; add `WorldSeed`, `SeededRng`, and JDK 25 `ClassFile` purity/no-float gate. |
| **2** | `29b0e00a` | [`02-generational-tables-gauges-and-event-heap.md`](02-generational-tables-gauges-and-event-heap.md) | `libs/wilds` | `OwnerId`/`ActorId`/`PlaceId`/`RuleId`, dense `EntityTable`, integer `LazyGauge`, `DueEventHeap` + `heapTicket`. |
| **3** | `0aaf30bb` | [`03-neutral-world-state-rules-and-procgen-seams.md`](03-neutral-world-state-rules-and-procgen-seams.md) | `libs/wilds` | `WorldState`, `RuleRegistry`, `WorldTopologyBootstrap` & `DescriptionComposer` seams, `JsonCodec`, `SnapshotCodec`, `WorldHash`. |
| **4** | `d8ed0e93` | [`04-commands-task-queues-events-and-projections.md`](04-commands-task-queues-events-and-projections.md) | `libs/wilds` | `WorldCommand`, two-phase `Wait` & `Travel` queues, fail-soft policy, `EventOutbox`, fogged `ObservationProjector`, golden replay test. |
| **5** | `aee4bb00` | [`05-headless-cli-and-wal-replay-rig.md`](05-headless-cli-and-wal-replay-rig.md) | `apps/wilds-headless` | Headless CLI (`run`, `replay --hash`, `inspect`) over `.jsonl` WAL streams. |
| **6** | `bbc37528` | [`06-modular-auth-and-http-security-library.md`](06-modular-auth-and-http-security-library.md) | `libs/auth` | `Principal`, Nimbus `JwtClaimsVerifier`, loopback-fused `DevLocalJwtIssuer`, OIDC PKCE/JWKS, `iw_bot_` codec, `AuthenticatedRouter`, `CsrfGuard`, `AuthSecurityGateTest`. |
| **7** | `9ba362ce` | [`07-server-worldhost-rest-and-sse-inmemory.md`](07-server-worldhost-rest-and-sse-inmemory.md) | `apps/wilds-server` | `SeatAuthorizer`, single-writer `WorldHost` (Commit-Before-Mutate + `CLOCK_ADVANCE`), REST `/api/v1/*`, `POST /api/v1/commands`, SSE `/api/v1/stream` over `InMemory*Store`. |
| **8** | `08da943e` | [`08-postgres-schemas-migrator-and-jdbi-adapters.md`](08-postgres-schemas-migrator-and-jdbi-adapters.md) | `apps/wilds-server` | `SqlMigrator`, neutral `auth` / `wilds_meta` / append-only `wilds_world` DDL, JDBI adapters, `PAUSE_WHILE_DOWN` crash recovery, Testcontainers ITs. |
| **9** | `128ef171` | [`09-minimal-ssr-console-and-end-to-end-system-tests.md`](09-minimal-ssr-console-and-end-to-end-system-tests.md) | `apps/wilds-server`, `apps/wilds-headless` | Escape-by-default SSR `/play` & `/chronicle` console + `wilds-ui.js` + `FullSpineIntegrationTest` (Browser/Auth/SSE -> Postgres crash recovery -> Headless `WorldHash` parity). |

---

## 4. Testing Pyramid Across the Suite

1. **Bytecode & Reflection Architecture Gates (`Ticket 01`, enforced on every build):** Mechanical verification of zero I/O, zero wall-clock, zero unseeded RNG, zero `float`/`double` in `WorldState`, and zero `HashMap`/`HashSet` state fields.
2. **Deterministic Unit, Property & Fuzz Tests (`Tickets 01–06`):** Equal-`dueAtMs` heap tie-breaking, slot generation recycling, Phase-1 pure non-mutation guarantees, 5,000-command randomized fuzz invariants, and golden `WorldHash` locks.
3. **Security Negative Gate (`Tickets 06–07`):** All 12 `AuthSecurityGateTest` scenarios (unannotated routes, non-loopback `dev-local` fuse, JWT `alg:none`/`HS256`/tamper/expiry/issuer checks, CSRF rejection, cross-seat spoofing, lateral bot-token escape, instant suspension).
4. **In-Memory Loopback Protocol ITs (`Ticket 07`):** Sub-second real-socket HTTP + SSE tests verifying `cmdId` idempotency, `409 STALE_STATE` optimistic concurrency, Commit-Before-Mutate store failure rollback, and `?since=cursor` SSE catch-up.
5. **PostgreSQL Persistence & Crash Recovery ITs (`Ticket 08`):** Singleton Testcontainers `postgres:17-alpine` verifying forward migrations, JIT OIDC user linking, `SQLException` split-brain immunity, and `kill -9` recovery with zero SSE cursor rewind.
6. **Full-Stack End-to-End Spine IT (`Ticket 09`):** Live `wilds-server` + real PostgreSQL + `dev-local` login + CSRF + SSR `/play` + live SSE + mid-run crash & restart + WAL export replayed through `apps/wilds-headless replay --hash` proving bit-for-bit `WorldHash` identity.

---

## 5. Non-Negotiable Definition of Done (Every Ticket)

- [ ] `~/.sdkman/candidates/mvnd/current/bin/mvnd test` passes cleanly across the entire reactor with **zero compiler warnings** (`-Xlint:all,-serial -Werror`).
- [ ] Every new `.java` and `pom.xml` file carries the **Apache License 2.0** header (`Copyright 2026 Samuel "Lord-Windy" Brown`).
- [ ] `PROJECTS.md` is updated whenever a module is added, removed, or promoted from `scaffold` to `in-development`.
- [ ] Zero gameplay-specific nouns (no Holds, Raids, Skills, Techs, Items, Parties, or Tithe Ships) leak into engine state, wire endpoints, or SQL migrations.
- [ ] Completed ticket file is moved from `tickets/<NN>-....md` to `tickets/done/<NN>-....md`, committed, pushed, and marked `done` in `st` (`st status <KEY> done`).
