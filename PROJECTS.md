# PROJECTS.md — KotharWorks Inventory

This file lists what lives in this monorepo. For coding standards, build
commands, and the `st` agent workflow, see [AGENTS.md](AGENTS.md).

**Status legend:**
- **stable** — public API settled, has tests, used by other projects
- **in-development** — actively being built; API may still change
- **prototype** — exploratory; expect rough edges
- **scaffold** — hello-world skeleton only; design in `planning/`

## Libraries (`libs/`)

### procgen — Seeded spatial math & procedural text
**Status:** scaffold · **Artifact:** `com.kotharworks:procgen` · **Package:** `com.kotharworks.procgen` · **Depends on:** —

Order-independent coordinate hashing (`WorldSeed`) and future hierarchical
slot/template prose generation. Zero external runtime dependencies.
See `planning/BigPlanningReview/carbon/03-ProceduralGeneration.md`.

### wilds — Ironwood Wilds simulation engine
**Status:** scaffold · **Artifact:** `com.kotharworks:wilds` · **Package:** `com.kotharworks.wilds` · **Depends on:** `procgen`

Headless, deterministic Java 25 simulation core for **Ironwood Wilds**: pure
logical-millisecond clock, zero I/O, zero wall-clock calls, command dispatch.
Currently holds the `WildsEngine` hello-world scaffold (`API_VERSION = 1`)
pinned by `WildsEngineTest`.
See `planning/README.md` and `planning/BigPlanningReview/carbon/README.md`.

## Applications (`apps/`)

### wilds-server — Ironwood Wilds server host
**Status:** scaffold · **Artifact:** `com.kotharworks:wilds-server` · **Package:** `com.kotharworks.wilds.server` · **Depends on:** `wilds`

Hexagonal server entry point for Ironwood Wilds (planned: Virtual-Thread HTTP/WS
adapters, PostgreSQL `auth`/`wilds_meta`/`wilds` adapters, server-rendered
multi-tab HTML UI). Currently prints the `WildsEngine` scaffold greeting via
`./mvnw -q compile exec:java -pl apps/wilds-server -am`.

## External dependencies

| Dependency    | Scope | Used by                        |
|---------------|-------|--------------------------------|
| JUnit Jupiter | test  | `procgen`, `wilds`, `wilds-server` |

## Dependency graph

```text
libs/procgen        (zero runtime deps)
  └── libs/wilds
        └── apps/wilds-server
```
