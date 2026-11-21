# Ironwood Wilds — GLM Planning Suite

> **Author:** GLM (zai-coding-plan) · **Date:** 2026-11-21 · **Status:**
> planning only — no code written, no tickets created, no repo files
> touched. Written for review and comparison against the other suites.

## 1. Reading order

| File | Contents |
|---|---|
| [IronwoodWildsGameplay.md](IronwoodWildsGameplay.md) | The brief: premise, pillars, households, skills, actions, PvM, raids, Favor economy, seasons, non-goals |
| [IronwoodWildsArchitecture.md](IronwoodWildsArchitecture.md) | Module map, the lazy deterministic simulation kernel, flat-table state, single-writer concurrency, the pattern catalogue, determinism discipline, testing strategy |
| [IronwoodWildsCommands.md](IronwoodWildsCommands.md) | The wire protocol: envelope, admission pipeline, queue semantics, worked examples, error taxonomy, bot contract |
| [IronwoodWildsProcgen.md](IronwoodWildsProcgen.md) | Seed hierarchy, the ten-pass world pipeline, lazy region materialisation, name grammar, the slot/template description system, season myth |
| [IronwoodWildsWebInterface.md](IronwoodWildsWebInterface.md) | One REST+SSE protocol for humans/bots/spectators, fog projections, the server-rendered tabs-per-character UI |
| [IronwoodWildsPersistence.md](IronwoodWildsPersistence.md) | `auth`/`wilds_meta` schemas with DDL, command-log-as-truth, snapshots, projections, migrations, crash recovery |
| [IronwoodWildsPrototype.md](IronwoodWildsPrototype.md) | "The Landing" slice: scope, a full worked session, module skeleton, code sketches, milestones M0–M5, `st` ticket mapping, risks, demo script |

### Diagrams & mockup (viewable assets)

Each diagram ships as editable `.dot` + rendered `.svg` + `.png` in
[diagrams/](diagrams/); the webpage mockup ships as openable HTML + SVG +
PNG in [web-mockup/](web-mockup/). They are also embedded inline in the
docs listed below.

| Asset | What it shows | Embedded in |
|---|---|---|
| [wilds-module-graph](diagrams/wilds-module-graph.svg) | Monorepo modules, purity split, runtime vs compile-time edges | Architecture §2 |
| [wilds-architecture](diagrams/wilds-architecture.svg) | The big picture: clients → adapters → WorldHost → engine → procgen → Postgres | Architecture §2 |
| [wilds-command-lifecycle](diagrams/wilds-command-lifecycle.svg) | A command from POST to world event, with every rejection path | Commands §3 |
| [wilds-action-lifecycle](diagrams/wilds-action-lifecycle.svg) | Action/queue state machine: two-phase validation, fail-soft, death-interrupt | Commands §4 |
| [wilds-procgen-pipeline](diagrams/wilds-procgen-pipeline.svg) | Seed → ten passes → RegionState/consumers, laziness boundaries | Procgen §3 |
| [wilds-persistence-flow](diagrams/wilds-persistence-flow.svg) | Truth (command log) vs cache (snapshots) vs projections, crash recovery | Persistence §5 |
| [play-page mockup](web-mockup/play-page.png) · [interactive HTML](web-mockup/play-page.html) | The `/play` webpage: tabs per character, queue editor, chronicle, fog map | Web §5.1 |

Re-render after editing any `.dot`:
`dot -Tsvg -o <name>.svg <name>.dot && dot -Tpng -Gdpi=150 -o <name>.png <name>.dot`
(the mockup PNG: `rsvg-convert -w 2160 -o play-page.png play-page.svg`).

Read Gameplay first for the *what*, Architecture second for the *how*, then
any other order. Every doc ends with a decision digest (`GP-D#`, `AR-D#`,
`CM-D#`, `PG-D#`, `WI-D#`, `PS-D#`) so disputes can cite a specific decision
rather than a vibe.

## 2. The whole plan in one paragraph

A text-based, procedurally generated colonisation sim played over REST+SSE
by humans, bots, and (later) evolved policies alike. Each player runs a
household of patient colonists whose queued, real-time actions a
deterministic, lazily-evaluated discrete-event engine resolves — same seed +
same command log = same world, always. Worlds generate lazily from
coordinate-hashed seed streams; goods shipped home convert to Favor, which
buys people back after the PvM deaths that are the game's only real sting;
raids on your preplanned hold cost goods, never people; seasons re-roll the
world and freeze the rules. The server is a modular monolith (pure `libs/`,
hexagonal `apps/`) on Java 25 virtual threads; Postgres stores the command
log as the source of truth with snapshots as cache, so any session replays
bit-for-bit — which is simultaneously the debug story, the content/observability
story, and the future genetic-algorithm fitness function.

## 3. Ten headline decisions

| # | Decision | Doc |
|---|---|---|
| 1 | Characters are patient executors; zero autonomy (anti-Sims) | GP-D2 |
| 2 | Needs are modifiers; only violence kills; death is season-permanent | GP-D3 |
| 3 | Lazy deterministic time: (dueMs, seq) event heap + 1 Hz pump; offline progress free | AR-D1 |
| 4 | Command log order *is* the timeline; log+snapshot persistence, not event sourcing | AR-D2 / PS-D1 |
| 5 | Flat entity tables + typed IDs; ECS shape, no ECS framework; patterns on behaviour, not state | AR-D3 / §5 |
| 6 | Single-writer world thread; virtual threads everywhere else; no reactive | AR-D4 |
| 7 | PvM as plannable autobattler; raids preplanned; raids cost goods, expeditions cost lives | GP-D6/D8 |
| 8 | One REST+SSE protocol for humans, bots, spectators; UI is just a client | WI-D1 / P8 |
| 9 | World gen: unbounded lazy regions from named positional seed streams; text descriptions generated, never stored | PG-D2/D3/D5 |
| 10 | `Doctrine` strategy seam + headless fast-forward + replay = the future GA on-ramp, shipped in the slice | §5.2 / M5 |

## 4. Lineage from the older planning (nothing was wasted)

| Old doc (planning/olderplanning) | What survives, where |
|---|---|
| `samvillage.md` | Two-phase validation + fail-soft queues (CM-D4/Commands §4); 1+4 queue cap (GP-D4); rules-as-data with version + `GET /v1/rules` (AR-A5); goal board (slice M2); golden replay + `--bless` discipline (AR §7); error envelope (Commands §2); event cursor feed (Web §2.2); births/lineage (post-slice, GP §2) |
| `biofabrica.md` | Weekly/season reset discipline + no-direct-trading (GP-D7/D10); PvM-as-autobattler (GP-D6); awareness/escalation dial, personal-first so newcomers aren't ganked (GP-D9); cooperative projects parked for season-end possibilities |
| `samgenai.md` | The `reset/observe/act/reward` environment seam mapped onto a household = future GA harness (Gameplay §11); bot-as-living-test pattern promoted to reference player (Commands §8) |
| `procgen_mvp.md` | The whole slot/template/category description system ports as-is conceptually into `rules/Descriptions` (PG-D5); lazy detail recursion; wear levels feeding from world depletion rows |

And from the existing scaffold: `WorldSeed.regionHash` is the determinism
primitive (PG §2); `WildsEngine`'s sealed `Command` + `logicalTimeMs` grow
into the kernel (Architecture §4); PROJECTS.md's `wilds-server` adapter plan
(`auth`/`wilds_meta` Postgres, server-rendered multi-tab HTML) is adopted
verbatim (Persistence §1, Web §5).

## 5. Assumptions made (flag if wrong — none are load-bearing yet)

1. **Death only from expeditions/raids-gone-expedition**, never from needs
   (walk-away safety). samvillage chose starvation death; Wilds' offline
   pacing argues against it.
2. **One household per account per season.**
3. **No player-to-player trading or chat in v1** (biofabrica rule); the
   chronicle is the only shared voice, which also keeps moderation at zero.
4. **~6-week seasons** on a single small server; 200-household envelope.
5. **Single-world server** — no sharding until proven necessary (AR §10
   records the escapes).
6. **The JDK built-in HTTP server + hand-rolled routing** (virtual-thread
   executor) rather than any web framework; JDBI is the one server-side
   dependency beyond the JDK, for Postgres.
7. **Email+password accounts**, no OAuth in the slice.
8. Fixed roster of 3 for the slice; full households (4 + growth) after.

## 6. Open questions left deliberately for Sam

None of these block the slice; all of them deserve your call before their
milestone:

1. **Queue cap 1+4** — inherited from samvillage. Wilds is slower-paced; a
   deeper queue (say 1+8) might fit the "walk away for hours" fantasy
   better. Cheap to change; it's one rules row.
2. **Real-time pacing** — 1 tick = 1 real second means a season is *long*.
   Comfortable? Or 1 tick = 2s / day-night compression?
3. **Household naming across seasons** — households get fresh names each
   season, accounts persist (PS §7). Or should the household name be the
   persistent identity (dynasty feel)?
4. **Ironwood scarcity (≤2 groves per world)** — intentionally cutthroat
   for the content/story value. Softer?
5. **Season-end narrative climax vs pure calendar** — both specified
   (GP §9); which is the default the first season ships with?

## 7. If this suite is adopted

- Drop these files into `planning/` (e.g. `planning/BigPlanningReview/glm/`)
  alongside the others being compared — include `diagrams/` and
  `web-mockup/` so the SVGs/PNGs stay viewable in the repo.
- The gameplay doc becomes the repo's `IronwoodWildsGameplay.md` (per the
  original ask) once the comparison is settled.
- The prototype doc's §6 list seeds the first `st add` batch under
  `tech-wilds` / `tech-procgen`.
- PROJECTS.md entries for `apps/wilds-headless` and `apps/wilds-bot` get
  scaffolded when M0/M5 begin, not before.
