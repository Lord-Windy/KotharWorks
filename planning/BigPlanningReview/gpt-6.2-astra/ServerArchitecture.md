# Server architecture — `samwilds` in Ptah

**One process should be able to run a season; one library should be able to run it without that process.** The live server supplies identity, a clock and durable storage. The lab supplies a scenario and an advanceable clock. Both ask the same C code what a person can see, whether an order is valid and what happens next.

```text
 browser: HTML forms / small JS       external bot: JSON over HTTPS
                 \                         /
                  TLS reverse proxy → samhttp
                                      |
                         apps/samwilds-server
                      auth → world service → PostgreSQL
                                  |            state + receipts + events
                           advance / command / observe
                                  v
 apps/samwilds-lab ─────────> libs/samwilds <──── libs/samwildsgen
        |                        rules + engine        ^
        └── seed preview / scenario creation ──────────┘
```

The server also calls `samwildsgen` at founding. The arrow from the generator to the core is a **build dependency**: the generator produces the world's definition in the core's public vocabulary. An already founded world, a hand-drawn test map and a replay can all run without invoking the generator.

## Proposed modules and ownership

| Proposed Ptah location | Boundary and public contract |
|---|---|
| `libs/samwilds/` (`<samwilds.h>`) | Headless C11 engine. Owns IDs, typed world definition, catalog, command/result types, player observations, simulation time and state transitions. Private plain modules: `rules.c` (including data-pack decoding from bytes), `world.c`, `actions.c`, `schedule.c`, `economy.c`, `observe.c`. Add `party.c` and `raid.c` when those rules exist. Uses samrena, samdata and a catalog decoder such as Ptah's existing cJSON dependency. No file handles, HTTP or SQL in its interface. |
| `libs/samwildsgen/` (`<samwildsgen.h>`) | Pure `(seed, generator version, profile, catalog) → WorldDef + generation report`. Called by the season founder and the lab's seed previewer. `layout.c`, `place.c`, `validate.c`; produces the same value types as a fixture. Cannot modify a running world. |
| `apps/samwilds-server/` | Composition root. `commands/` translates JSON requests or form posts; `domain/` coordinates admission, transactions and reads; `ports/` declares the small clock/store contracts; `adapters/` contains libpq persistence, auth, `samhttp` routes and `samhtml` views. One executable owns the mutable world. A timer calls the same service as a request. |
| `apps/samwilds-lab/` | Local executable for a seed preview, scripted virtual-time runs, record verification and paired experiments. Drives the core as a caller. A policy receives a household observation; only the harness may advance time, change initial conditions or compare omniscient outcomes after a run. |
| `apps/samwilds-bot/` | Reference external client. Reads visible JSON, chooses a person and posts an order. libcurl + cJSON; **no engine link**. If this client needs a private header, the player protocol is missing something. |
| `libs/samwilds/content/` | Versioned, authored pack: item/work/tech/building/contract definitions, site archetypes and text. Both live and lab load the same bytes. The server archives its exact pack at founding. See [content authoring](WorldGenerationAndContent.md). |

Use the normal `ptah_add_library`/`ptah_add_executable` layout and public umbrella headers when this becomes an implementation. An ordinary C module is enough for inventory or skills; an independently linkable library is useful for the generator because there are real consumers that don't need it. There is no benefit yet to deploying a mining service, a clock service or an event broker.

## The useful OOP patterns, translated into C

| Pattern | Representation | Example / reason to use it |
|---|---|---|
| **Command** | A tagged value: `kind`, typed actor ID, expected queue revision, bounded payload union. The dispatcher returns a receipt and typed changes. | Clicking *mine* and a bot choosing *mine* are the same input. Store the normalized command to replay it. |
| **Prototype / Type Object** | Immutable rows keyed by stable content IDs. Instances contain a prototype ID and mutable fields only. | `building.haul_shed` defines its costs and effect. An instance belongs to one household and records when it became active. No subclass per building. |
| **Strategy** | A small, compiled verb table. Each entry supplies `validate_shape`, `try_start` and `finish` functions for one kind of work. | `gather.ironstone` and a new `gather.rushes` use the same gather handler with different rows. `party.explore` needs a new handler because it owns several people until it finishes. |
| **Entities and components** | Stable world-local IDs; typed tables for `Person`, `Skill`, `Cargo`, `Queue`, `Household`, `Stock`, `Site`, `Building`. Address relationships by ID. | A person can gain a skill without replacing their object; a dead person retains a name for history. One can inspect the whole queue without teaching an item how to tick. A generic ECS query engine would solve a problem this slice doesn't have. |
| **Ports/adapters** | Plain structs of function pointers plus a context pointer at external boundaries; plain function calls elsewhere. | Wall time vs fake time is a real substitution. A PostgreSQL commit vs a throwaway in-memory trial is another. A source of truth per subsystem would break the ore transfer. |
| **Observer / projection** | A committed transition emits typed facts with audience and cause. Views format those facts; observers cannot call back into a mutation. | The shipment earns marks in the economy transition. The event renderer *describes* the marks; replaying a feed delivery cannot award them again. |

Conceptual calls (names describe contracts, not existing APIs):

```text
samwilds_create(season_arena, pinned_rules, world_def) → world
samwilds_begin(world, scratch)                       → staged transition
samwilds_advance(staged, through_s)                  → changes + next due time
samwilds_command(staged, household, command)         → result + changes
samwilds_publish(world, committed_transition)       → new committed view
samwilds_observe(world, household_or_public, as_of_s, output_arena) → view
```

Use typed errors (`SamWildsError`, `_NONE = 0`) and stable wire reason codes. Preconditions, costs, durations, XP and effects belong to the core. The HTTP adapter translates errors to status codes; the SQL adapter persists effects; neither decides what a unit of iron buys. An experiment changing the haul-shed bonus should touch a pack row, not either adapter.

## A command crossing the process

1. A synchronous `samhttp` handler authenticates the session/key, limits and decodes the body, resolves the season membership, then calls the world service. A browser form constructs the same command value as the JSON decoder.
2. There is one logical writer per active world. For the pilot, a per-world mutex serializes the timer and request handlers. The server also holds a PostgreSQL session advisory lock for that world: a second process cannot serve a stale mutable copy. Loss of that connection requires reacquiring ownership and reloading state.
3. The service checks for a prior receipt, samples the authoritative clock inside the serialization boundary, and drains due work. The core stages touched rows, new deadlines and facts in scratch memory; it never emits an irreversible external side effect. The service applies the new command against that state. See [ordering and contention](TimeAndCommands.md).
4. libpq commits each complete unit of work atomically. The command, its receipt and **its** effects share one commit; earlier deadline batches can commit independently. Only after success does the service publish the staged state, refresh the next wake-up and answer the client. A failed or uncertain commit is recovered from storage before further writes. The [storage design](AccountsAndStorage.md) spells this out.
5. An observation uses a committed state and matching stream cursor. It catches up first if necessary. Idle reads don't need a write transaction or a simulation event. A feed reader may miss the live response and reconnect later: committed facts remain addressable by cursor.

**Memory lifetimes:** immutable rules and baseline map live for the season. Mutable active records use bounded/reusable slots; IDs are never recycled in that world. Only changed records are staged per transition (a whole tiny fixture copy is fine in the first lab). Reserve memory for publication before the DB commit or reload on publication failure. Handler strings stay in the request arena, and only decoded values/owned copies cross the boundary. Reset transaction/output arenas after use. Historical actions and events live in PostgreSQL, not in an ever-growing arena attached to the live process.

## Reuse what already works in Ptah

| Evidence in this checkout | What to take from it |
|---|---|
| [Ptah inventory](../ptah/PROJECTS.md), [samvillage server](../ptah/apps/samvillage/README.md), [bot](../ptah/apps/samvillage-bot/README.md) | Headless rules, queue diagnostics and a real external bot test are excellent precedents. `samvillage` also assumes a ticking needs simulation, a world-wide bearer and file saves. Those assumptions conflict with patient people, independent accounts and durable deadlines; build a sibling engine rather than fork its game loop. |
| [samgrognard console](../ptah/apps/samgrognard-console/README.md) | Fix seed cohorts, retain interesting runs and replay recorded inputs with pinned rules. Its turn resolver and PvP visibility model are specific to that game. |
| [samhttp response contract](../ptah/libs/samhttp/include/samhttp/response.h), [samdashboard](../ptah/apps/samdashboard/README.md) | HTTP/1.1 buffered responses + samhtml forms can serve the first interface. Use a TLS reverse proxy for deployment. Persistent streams/WebSockets would require transport work; the game does not need them to issue commands. |
| [samtechtree migrations](../ptah/apps/samtechtree/migrations), [SamGenaiEnvironment](../ptah/libs/samgenai/include/samgenai.h) | Follow the owned-schema migration pattern. The existing tech-tree app models *project epics*; the in-world tech DAG belongs in the content pack. `reset/observe/act/done` is a useful future lab adapter, but the genetics are scaffolded and are not a dependency of the sim. |

**When to split further:** measure oldest due-job lag, command lock/commit time and poll traffic with idle and busy households. First reduce redundant reads and batch due work. If independent seasons contend, assign whole worlds to different writers. Splitting a household, its stock and its workers among services would turn one local transaction into a distributed correctness problem. The useful pilot performance check is that adding 1,000 *idle* people creates zero additional timer jobs.
