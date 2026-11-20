# Ironwood Wilds — Planning Pack

- **Prepared by:** Kimi
- **Date:** 2026-11-20
- **Status:** Design only. No implementation, repository changes, or ticket creation is included.

## 1. The idea in one paragraph

Ironwood Wilds is a persistent, text-first, API-driven frontier simulation. A player or program directs a household of several independent, deliberately **patient** characters. Characters do exactly what they are commanded to do, one timed action at a time, with a short queue; they do not invent hobbies while idle. The interesting behaviour comes from interacting resources, geography, skills, technology, construction, shipments home, monsters, and other households—not from scripted story beats. Humans and bots use the same observation and command protocol, which makes the world watchable, testable, and eventually suitable for genetic controllers without making AI a prerequisite for the game.

## 2. Recommended decisions

These are recommendations to make the first implementation tractable, not immutable laws.

| Area | Recommendation | Reason |
|---|---|---|
| Project naming | Use **`samwilds`** for the code family; Ironwood Wilds remains the public game name | Fits Ptah conventions without naming every module after a different game |
| First architecture | One authoritative server process, one logical writer per world, many thin clients | Avoids distributed-systems work before the game loop exists |
| Core placement | `libs/samwilds`: headless, deterministic, no HTTP/database/wall-clock calls | Supports unit tests, replays, accelerated bots, and alternate adapters |
| Server placement | `apps/samwilds-server`: application services plus HTTP, HTML, clock, and PostgreSQL adapters | External integrations earn hexagonal boundaries; the library itself stays plain modules |
| Clients | Separate `samwilds-cli` and `samwilds-bot`, neither linking the engine | Proves the public protocol is sufficient and prevents privileged bots |
| Entity model | Explicit structs, stable IDs, ID-indexed collections, and small per-entity records—not a generic ECS initially | C benefits from visible data and explicit lifetimes; an ECS would add machinery before a proven need |
| Behaviour extension | Tagged command payloads, action-handler registries (Strategy), immutable definition tables (Prototype), ordered domain events | Supplies the requested extensibility without faux C++ inheritance |
| Time | Integer logical time and a due-event priority queue; advance to a requested time, never iterate empty ticks | Matches lazy evaluation and permits fast-forward experiments |
| Transport v1 | REST/JSON commands and cursor-based event polling | `samhttp` already supports this shape and has no WebSocket support |
| Browser v1 | Server-rendered `samhtml` pages plus small vanilla-JS polling islands; usable without JS | Text-first, accessible, no SPA or Node dependency |
| Persistence | PostgreSQL for identity/control plane; versioned world snapshots plus a command/event journal; query projections only when needed | Durable recovery and replay without maintaining every mutable field twice |
| Procedural generation | Versioned pipeline, coordinate-derived random streams, bounded world generated on demand, persisted base artifacts plus modifications | Generation order must not change the world |
| Multiplayer transport security | TLS at a reverse proxy; secure browser sessions and scoped bot tokens | `samhttp` is deliberately plain HTTP/1.1 |
| AI | Scripted external bot first; `samgenai` experiments after the game loop is demonstrably usable | The simulation must be valuable before evolving controllers for it |

## 3. Document map

1. **[IronwoodWildsGameplay.md](IronwoodWildsGameplay.md)** — game pillars, loops, characters, skills, technology, PvM, holdings, shipments, death, and seasons.
2. **[IronwoodWildsArchitecture.md](IronwoodWildsArchitecture.md)** — Ptah module layout, C-friendly design patterns, runtime ownership, dependency direction, scaling, and testing.
3. **[IronwoodWildsSimulation.md](IronwoodWildsSimulation.md)** — command lifecycle, queues, lazy scheduling, event ordering, action semantics, replay, and invariants.
4. **[IronwoodWildsProceduralGeneration.md](IronwoodWildsProceduralGeneration.md)** — deterministic world pipeline, geography, sites, ecology, starting locations, persistence, and validation.
5. **[IronwoodWildsWebAndProtocol.md](IronwoodWildsWebAndProtocol.md)** — REST API, JSON envelopes, event polling, browser interface, multiple character tabs, and bot parity.
6. **[IronwoodWildsPersistence.md](IronwoodWildsPersistence.md)** — account model, PostgreSQL schemas, snapshots/journal, revisions, authentication, recovery, and world resets.
7. **[IronwoodWildsVerticalSlice.md](IronwoodWildsVerticalSlice.md)** — a concrete thin but complete first world, demo walkthrough, acceptance tests, and exclusions.
8. **[IronwoodWildsRoadmap.md](IronwoodWildsRoadmap.md)** — dependency-ordered milestones, risk retirement, later simulation depth, and the future genetic-algorithm seam.

## 4. The architectural spine

```text
             Browser tabs          CLI          Scripted bot / future GA
                    \                |                    /
                     +--------- public protocol ----------+
                               REST + JSON
                                     |
                         TLS reverse proxy (deployed)
                                     |
                         apps/samwilds-server
                 auth -> application -> single-world runtime
                    HTML/HTTP adapters     |       PostgreSQL adapter
                                           v
                                  libs/samwilds
                    model | rules | commands | actions | agenda
                    combat | procgen | observation | serialization
                                           |
                   samrena + samdata + samstring + cjson (records)
```

The crucial rule is that **the core decides; adapters translate**. HTTP never edits a character directly, SQL contains no game rules, the browser never predicts authoritative outcomes, and a bot receives no hidden state.

## 5. What “lazy” means here

Lazy does **not** mean that a three-minute job finishes only when its owner logs in. It means the engine stores an absolute completion time and performs no work for the intervening empty seconds. A timer or admitted command asks the runtime to advance to logical time `T`; the engine completes every scheduled event at or before `T` in stable order and then services the request. A scheduled wake-up at the earliest due event lets other players observe completion promptly even when the original actor is absent. Ordinary reads use committed observations/projections; any special exact-freshness read must use the same writer boundary rather than advancing a private copy.

The first implementation advances one whole world to a single `T`. Per-character and per-region clocks are a later optimisation, because they introduce cross-region dependency problems immediately.

## 6. Principles that should survive redesigns

- **Patient people, living world.** Player characters wait without autonomous choices. Monsters, weather, plants, and scheduled world systems may still change around them.
- **One command path.** Browser, CLI, scripted bot, test harness, and live learned controller use the same commands and observations; isolated training uses the equivalent vocabulary without production network authentication.
- **Facts before flavour.** Procedural descriptions render underlying terrain, ecology, and history facts; flavour text does not secretly create game state.
- **Stable identity and ordering.** IDs are never recycled inside a world; event and action ordering never depends on hash-table iteration or thread timing.
- **Explicit failure.** An action that was legal when queued can fail when it starts. That produces a visible event; it does not silently wait or invent resources.
- **No irreversible acknowledgement before durability.** A command is not acknowledged as committed until its journal transaction succeeds.
- **Separate versions.** API, save format, ruleset, and procedural-generator versions are independent and recorded.
- **A restart is not a reset.** Process recovery resumes a world. A season/reset creates a new world ID and leaves the old world archivable.
- **Earn depth through events.** The best stories should come from recorded actions and consequences, not a large up-front lore generator.

## 7. Deliberate non-goals for the first slice

No PvP, WebSockets, microservices, general-purpose ECS, scripting language, player market, full needs simulation, infinite world, neural/GA controllers, detailed erosion, or dozens of resources. These are not rejections of the vision; they are barriers against spending every Friday on infrastructure without producing a world worth watching.

## 8. Existing Ptah assets to reuse

- **`samrena` / `samdata`:** allocation, vectors, maps, sets, hashing, seeded RNG.
- **`samhttp` / `samplex` / `samhtml` / `samstring`:** HTTP, async I/O substrate, safe HTML, and serialization helpers.
- **`samvillage` as precedent, not a dependency:** action double-validation, short queues, rules served to clients, versioned saves, golden determinism tests, and an external autopilot.
- **`samgrognard` as precedent, not a dependency:** stable event ordering, audience-filtered observations, record verification, replay/fork thinking, and bot API versioning.
- **`samtasker` / `samdashboard` / `samtechtree` as precedents:** function-pointer repository ports, libpq adapters, schema ownership, migrations, composition roots, and real-socket self-tests.
- **`samgenai` later:** its environment callback model is a useful accelerated-training seam, but it should not be linked into the production server.

The largest warning from `samvillage` is also useful: a fixed tick, a single world token, positionless travel, and snapshot-only storage are suitable for a local prototype but do not satisfy this project's multi-account, spatial, lazy, persistent requirements. Reuse its proven boundaries, not those specific shortcuts.
