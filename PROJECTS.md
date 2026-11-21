# PROJECTS.md — KotharWorks Inventory

This file lists what lives in this monorepo. For coding standards, build
commands, and the `st` agent workflow, see [AGENTS.md](AGENTS.md).

**Status legend:**

- **stable** — public API settled, has tests, used by other projects
- **in-development** — actively being built; API may still change
- **prototype** — exploratory; expect rough edges
- **scaffold** — hello-world skeleton only; design in `planning/`

## Libraries (`libs/`)

### wilds — Ironwood Wilds simulation engine

**Status:** in-development · **Artifact:** `com.kotharworks:wilds` · **Package:**
`com.kotharworks.wilds` · **Depends on:** — (zero runtime dependencies)

Headless, deterministic Java 25 simulation core for **Ironwood Wilds**: pure
logical-millisecond clock, integer-only state, zero I/O, zero wall-clock calls,
command dispatch. Holds the `WildsEngine` scaffold (`API_VERSION = 1`) pinned by
`WildsEngineTest`, the domain-separated seeded RNG primitives `WorldSeed`
(FNV-1a + SplitMix64) and integer-only `SeededRng` in `com.kotharworks.wilds.seed`,
and the JDK 25 `java.lang.classfile` bytecode & reflection purity gate
(`WildsArchitectureGateTest`). Ticket 02 adds the generational wire IDs
`OwnerId`/`ActorId`/`PlaceId` + `RuleId` with parse/round-trip validation in
`com.kotharworks.wilds.id`, and the dense `EntityTable`, integer `LazyGauge`,
and `(dueAtMs, seq)` `DueEventHeap` simulation structures in
`com.kotharworks.wilds.sim`. Ticket 03 adds the neutral `WorldState` schema and
entity rows (`OwnerRow`, `PlaceRow`, `ActorRow`, `TaskSpec`, `ActiveTask`) in
`com.kotharworks.wilds.state`, the compile-time `RuleRegistry` in
`com.kotharworks.wilds.rules`, the `WorldTopologyBootstrap`/`DescriptionComposer`
procgen strategy seams with deterministic stubs in `com.kotharworks.wilds.worldgen`,
and the zero-dependency `JsonCodec`, `SnapshotCodec`, and canonical SHA-256-based
`WorldHash` in `com.kotharworks.wilds.replay`. See
[`planning/README.md`](planning/README.md) and
[`planning/ironwood_wilds_planning/README.md`](planning/ironwood_wilds_planning/README.md).

## Applications (`apps/`)

### wilds-server — Ironwood Wilds server host

**Status:** scaffold · **Artifact:** `com.kotharworks:wilds-server` ·
**Package:** `com.kotharworks.wilds.server` · **Depends on:** `wilds`

Hexagonal server entry point for Ironwood Wilds (planned: Virtual-Thread HTTP/WS
adapters, PostgreSQL `auth`/`wilds_meta`/`wilds` adapters, server-rendered
multi-tab HTML UI). Currently prints the `WildsEngine` scaffold greeting via
`mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-server -am`.

## External dependencies

| Dependency    | Scope | Used by                 |
| ------------- | ----- | ----------------------- |
| JUnit Jupiter | test  | `wilds`, `wilds-server` |

## Dependency graph

```text
libs/wilds           (zero runtime deps)
  └── apps/wilds-server
```
