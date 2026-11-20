# Server architecture — a library with a clock supplied by its caller

**Keep the rules in a C library that can finish a season without opening a socket, reading the wall clock or connecting to a database.** The live server supplies time, identity and durability. The lab supplies the same library with a seed, some orders and a virtual clock. That gives you an online world and a population experiment without having to write the simulation twice.

## Proposed Ptah pieces

Arrows mean “calls or depends on”. These are proposed names, not existing libraries.

```text
browser ──HTML/forms──┐
                     ├── samwildsd ────────► samwilds (rules and state transitions)
samwilds-bot ──JSON───┘       │                   ▲
                             ├──► samwildsgen ───┘ (produces core value types)
                             └──► PostgreSQL
samwilds-lab ───────────────────► samwildsgen + samwilds
```

| Target | Owns | Main dependencies |
|---|---|---|
| `libs/samwilds/` | World and entity IDs, action plans, queue and time ordering, inventories, skills, tech, hold, relief ledger rules, household observations and domain events. Public entry: `samwilds.h`. | `samrena`, `samdata`. Accepts typed definitions and blueprints; no JSON or I/O. |
| `libs/samwildsgen/` | Reads pack **bytes** into validated, immutable definitions; builds the founding blueprint and its descriptions from the seed. The server and the lab both need this compiler. Public entry: `samwildsgen.h`. | `samwilds` value types, `samrena`, `samdata`, private `cJSON` for the authoring format. |
| `apps/samwildsd/` | Composition root; authentication, per-season writer, wall-clock adapter, PostgreSQL transactions, HTTP/JSON and HTML adapters. A `ports/` seam for reading time and committing a transition, `adapters/` for the concrete systems, `commands/` for request decoding. | Both libraries, `samhttp`/`samplex`, `samhtml`/`samstring`, `libpq`, `cJSON`. |
| `apps/samwilds-lab/` | In-process experiment runner: virtual time, fixed or generated scenarios, scripted household policies, input tapes, replay and comparisons. The memory store actually gets used here. | Both libraries. No server or database to run a cohort. |
| `apps/samwilds-bot/` | A small external reference client. Reads a view, chooses one order or waits, and records its reason. Its success is the test that the public API is sufficient. | `libcurl`, `cJSON`; no engine link. |

Inside `samwilds`, start with plain modules: `orders`, `schedule`, `inventory`, `people`, `progression`, `hold`, `relief`, `observe`. They can share a private world header; only the umbrella API is public. Add `expedition` and `raid` when they have rules to implement. These are boundaries of responsibility, not daemons. Follow Ptah's arena-first `_create(Samrena *, …)`, umbrella headers and co-located `test/` convention when the targets are eventually built.

## What the design patterns buy you in C

| Pattern | Concrete use | Example change |
|---|---|---|
| **Command**: enum tag + typed union | A `queue.append` contains a person ID and an array of typed orders; a `household.recruit` carries a roster revision. Identity is supplied separately by the authenticated caller. Same values from HTML, HTTP or lab. | A bot does not need a second way to award XP. It can only order work. |
| **Strategy**: small static table of functions | An action kind selects a handler that checks start conditions, produces reservations/duration, and plans completion. The scheduler knows when; the handler knows what. A separate, restricted policy table can later resolve hold defence plans. | `mine` and `fell` both use the gather handler. A party expedition needs a new handler because several people are committed together. |
| **Prototype**: immutable definition + mutable instance | The pack defines a packing shed's cost, work time and unlocked offer. A built shed stores its own ID, definition ID, owner and state. People and sites follow the same rule. | An author can add a slower, more productive seam by supplying a different gather definition; no new C type or database table. |
| **Typed entity records** | People have a location, skills and queue. A stock stack has an owner/container, good and quantity. Stable IDs join them; pointers never escape into saves. | A raid can ask for exposed, unreserved goods without iterating every component of every entity. A universal ECS would not simplify that query yet. |
| **Ports and projections** | A storage port commits one change set; a clock port supplies a target second. A projection turns committed facts into the member view or the publishable journal. In C a port can be a function table with a context pointer. | Swap a DB commit for an in-memory commit in the lab. Swap an HTML reader for a bot without changing visibility or game rules. |

There is one explicit resolution path for a consequence. For example, returning timber transfers pack → store, advances the delivery counter, grants Timber Framing if newly eligible, and records those facts **together**. An event consumer does not race another consumer to decide whether the tech exists. Events tell the rest of the system what was decided.

## The engine boundary

The following names describe the planned contract rather than a promised C ABI:

| Operation | Contract |
|---|---|
| `samwilds_create` | Given an arena, pinned definitions and a blueprint, make a world. A fixed fixture and a generated region enter here on equal terms. |
| `samwilds_next_due` | Return the earliest due work key, or **none** for a quiet world. No clock read. |
| `samwilds_plan_due` / `samwilds_plan_command` | Given committed state, an explicit time and a due key or authorized order, calculate a change set: row changes, reservations, schedule entries, events and any receipt result. No external side effects. |
| `samwilds_apply` | Install a planned change against its expected world revision. The live writer calls this only after durable commit; the lab can apply immediately. |
| `samwilds_observe` | Given a committed revision and an audience, return a value view: people, visible places, queues, stock, offers, reasons and journal cursor. Policies never receive a world pointer. |

**Memory lifetime:** pack data lives for a season. World state uses stable-ID slots and reusable job/queue storage. A scratch arena holds one plan through commit or discard; a request arena holds only decoding/rendering. Copy an order into writer-owned memory before a request ends. Retain the journal in storage, not by appending into the world arena forever.

## Who is allowed to advance the world?

One logical writer owns each live season. HTTP workers authenticate, parse and send it a bounded message. The writer wakes for the next deadline or an inbound order, settles work in [the specified order](CommandsAndTime.md), and commits a change set. Its deadline heap contains due *jobs*, not everyone who could do a job. One due entry costs a heap removal/insertion (logarithmic in pending deadlines); an idle person has no entry. This is useful even when the first server runs exactly one season on one process.

The live writer holds a PostgreSQL advisory lock on a dedicated connection for the season and checks the expected world revision in each transaction. Loss of the lock/connection stops that writer; a replacement reloads the committed state and due index. A failed commit discards the tentative plan. An ambiguous commit or failed in-memory installation forces reload before serving the next change. Reads get a consistent observation from the owner after a time barrier; restart and retry details are in [AccountsAndStorage.md](AccountsAndStorage.md).

Apply the same idea to environmental depth. Suppose a worked coppice later regrows one unit per game day up to a cap. With nobody cutting, derive the quantity from the last cut and the elapsed intervals; don't wake every tree at dawn. If regrowth can complete a shared objective or another rule listens for a threshold, schedule that crossing explicitly. A read may calculate a value but cannot be what makes the valley change. This follows the useful split between land fields and local history in Ptah's `planning/samsimulator.md`.

If demand grows, first measure deadline lag, catch-up work, store commit latency and per-season inbox length. Separate independent seasons across processes before attempting to give two writers pieces of one household's inventory. A transport change, an extra writer for another season, or a read-only journal reader should not change a recorded outcome.

## Where Ptah already helps

Paths below are relative to the existing `ptah/` checkout.

- `libs/samvillage/`, `apps/samvillage/` and `apps/samvillage-bot/` demonstrate engine/server/external-client separation, timed queues, skill rules and bot soaks. The village has a tick clock, autonomous needs and one seed-derived world token; adopting that engine wholesale would import the wrong time and ownership rules.
- `libs/samgrognard/` and `apps/samgrognard-console/` demonstrate audience-limited observations, fixed-seed cohorts, record verification and forks. Borrow the discipline; a household should not wait for the entire server to submit a turn.
- `libs/samtasker/include/samtasker/repository.h` shows a real C function-table port. `apps/samdashboard/` and `apps/samtechtree/` demonstrate HTML, form commands and app-owned PostgreSQL schemas. The latter manages **project epics**; the in-game tech DAG belongs in a Wilds content pack.
- `libs/samhttp/` serves HTTP/1.1, plain HTTP, close-per-request; `samhtml` renders escaped text. That's enough for a text client behind a TLS reverse proxy. Streaming and socket upgrades are a separate transport project if evidence warrants them.
- `libs/samgenai/include/samgenai.h` already defines the `reset / observe / act / done` environment seam. Later, a lab adapter can encode the household view, decode bounded actions, advance to the next decision time, and score deliveries over a fixed horizon. The header currently labels genetics synthetic; the slice works with no `samgenai` dependency.
