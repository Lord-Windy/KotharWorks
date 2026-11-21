# KotharWorks Planning Directory

## Active Planning Suites

- **[`ironwood_wilds_planning/`](ironwood_wilds_planning/README.md) — Ironwood Wilds Master Architecture & Implementation Suite**
  - [`IronwoodWildsGameplay.md`](ironwood_wilds_planning/IronwoodWildsGameplay.md) — Worldbuilding, "Patient Hands, Living People" personas & journals, skills, Tech Tree, Tithe Ship, raids & PvM
  - [`01-ServerArchitectureAndPatterns.md`](ironwood_wilds_planning/01-ServerArchitectureAndPatterns.md) — Generational dense entity schemas, GoF design patterns, Virtual-Thread `WorldHost` & `WorldHash` determinism gates
  - [`02-CommandsAndLazySimulation.md`](ironwood_wilds_planning/02-CommandsAndLazySimulation.md) — `POST /api/v1/commands` (`cmdId`), integer `LazyGauge` + `DueEventHeap`, Two-Phase fail-soft queues & `heapTicket` cancel
  - [`03-ProceduralGeneration.md`](ironwood_wilds_planning/03-ProceduralGeneration.md) — Multi-pass geography, Waypoint graphs, Myth flags, `NameGrammar`, `PersonaGenerator`, `JournalComposer` & `procgen_mvp` prose
  - [`04-WebInterfaceAndProtocols.md`](ironwood_wilds_planning/04-WebInterfaceAndProtocols.md) — REST + SSE contract, `/play` Multi-Tab Character Deck ([interactive prototype](ironwood_wilds_planning/prototype/index.html)), and `/chronicle` Observatory
  - [`05-AccountsAndPersistenceSchemas.md`](ironwood_wilds_planning/05-AccountsAndPersistenceSchemas.md) — `auth` (zero-password OIDC links & Bot keys), `wilds_meta`, and `wilds_world` (`command_wal` truth + checkpoints) PostgreSQL schemas
  - [`06-VerticalSliceAndRoadmap.md`](ironwood_wilds_planning/06-VerticalSliceAndRoadmap.md) — Slice 0 worked session, Milestones `M0–M5`, `st` ticket backlog, post-implementation review checklist & Phase 2 GA bridge
  - [`07-AuthAndSecurityArchitecture.md`](ironwood_wilds_planning/07-AuthAndSecurityArchitecture.md) — Modular `libs/auth` (`Nimbus JOSE+JWT`), 3-layer AuthN/AuthZ, loopback `dev-local` -> Keycloak OIDC, BFF CSRF & `AuthSecurityGateTest`

## Historical / Upstream Concept Notes

- **[`olderplanning/`](olderplanning/)** — Earlier C and prototype specifications (`procgen_mvp.md`, `samvillage.md`, `biofabrica.md`, `samvm.md`, `samgenai.md`) whose core ideas are carried forward into KotharWorks.
