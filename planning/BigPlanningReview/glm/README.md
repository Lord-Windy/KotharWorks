# GLM — Ironwood Wilds Planning Set

**Author:** GLM (zai-coding-plan, glm-5.3) · **Date:** 2026-11-20
**For:** Sam — planning only, nothing implemented, nothing committed,
no st tickets created.

## The documents

| File | Answers |
|---|---|
| [IronwoodWildsGameplay.md](IronwoodWildsGameplay.md) | What the game *is*: pillars, household, time, skills/tech, Charters, PvM, raids, seasons, the show-off surface |
| [IronwoodWildsServerArchitecture.md](IronwoodWildsServerArchitecture.md) | Module map (`samwilds`/`samterra`), threading, determinism contract, ECS vs OOP, the OO→C patterns table, rules-as-data, transport ladder |
| [IronwoodWildsCommandsAndSimulation.md](IronwoodWildsCommandsAndSimulation.md) | Wire format, the three validation gates, in-tick ordering, the lazy completion-heap time model in full, the event stream, a worked example |
| [IronwoodWildsProcgen.md](IronwoodWildsProcgen.md) | World = seed+params ⊎ journal diffs, lazy LOD generation, pass pipeline, technique menu, generation-by-simulation, statistical map QA |
| [IronwoodWildsWebInterface.md](IronwoodWildsWebInterface.md) | Server-rendered HTML + one JS island, tab-per-person, polling→SSE→WS ladder, spectate/chronicle/timelapse, auth |
| [IronwoodWildsAccountsAndPersistence.md](IronwoodWildsAccountsAndPersistence.md) | Accounts model, PG schemas (DDL sketch), snapshot+journal vs normalized world state, projections, season lifecycle, backups |
| [IronwoodWildsPrototypePlan.md](IronwoodWildsPrototypePlan.md) | The vertical slice, milestone-by-milestone in Friday-sized units, risk register, first three tickets |

## The single organizing insight

The repo already contains four threads of this exact idea, and
Ironwood Wilds is their convergence:

- **samvillage** is a working miniature (needs + timers + queues +
  production + events, API-driven, deterministic) whose TRD §1.1
  *literally lists this project as phase 2*: "persistent hosted world,
  multiple players, real bots running 24/7".
- **biofabrica.md** is the game-design core: player controls many units
  via API, two interlocking loops (combat ⇄ resources), no PvP.
- **samsimulator.md** contributes the world rule — land as seeded pure
  fields, state only where there's history, replay/fork from day one.
- **samgrognard** contributes the discipline — records, hash chains,
  goldens, replay/forks — which is what makes a persistent world
  debuggable, forensicable, and *content-generating*.

So the plan is conservative on architecture (every move has an
in-house precedent) and spends its novelty budget in exactly one
place: the lazy, deterministic, multi-household world kernel
(`libs/samwilds` + `libs/samterra`).

## Ten decisions worth comparing across LLMs

1. Engine = pure deterministic lib (`samwilds`), server = thin
   hexagonal app. (samvillage D2 shape, scaled.)
2. Procgen split into its own lib (`samterra`) — reusable by
   samsimulator and procgen_mvp.
3. **ECS sparse-set storage** + table-driven systems, not an object
   model; full OO→C patterns table provided (Command/Strategy/Prototype/
   Observer/… all have proven C equivalents in this repo).
4. Single sim thread, single-writer world, command inbox / event
   outbox; HTTP reads served from a published per-tick view.
5. **Command-sourced determinism**: journal + snapshots + per-tick
   world hash (the samgrognard dividend: recovery, forensics,
   timelapse, and later the GA fitness harness for free).
6. Lazy time model: completion-heap timestamps, progress computed on
   read, O(due) ticks, offline progression free — Sam's "lazily
   evaluated" instinct formalized.
7. Rules in two tiers: season-tweakable balance **data** vs mechanics
   **code**, both versioned and hashed (content cadence without
   recompiles).
8. World = pure seeded fields ⊎ journal diffs; lazy hierarchical LOD,
   freeze-on-touch; statistical gates as map QA; worldgen passes run
   as ECS systems (season gimmicks are data; "generation by
   simulation" for ruins/ecology).
9. Persistence: accounts relational in PG; world = snapshot+journal,
   SQL projections for leaderboards — the DB is a store, not the truth.
10. Web: server-rendered HTML + one vanilla JS island, tab-per-person;
    transport ladder polling → SSE → WebSocket-as-lib, commands always
    POST; public spectate/chronicle/timelapse are first-class (the
    content thesis) — and the UI uses only bot-visible endpoints.

## Explicit non-positions (left open on purpose)

Final lib name (`samwilds` is a suggestion), lore names beyond working
labels, balance numbers, WS timing, birth/lineage scheduling, communal
projects, GA integration design (samgenai's interactive-environment
problem kind is the acknowledged landing zone — noted, not designed).
