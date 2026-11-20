# Ironwood Wilds — Fable's plan

**2026-11-20.** A plan set, written to be read and compared. Nothing in
`~/dev/ptah` was changed and no tickets were created or edited.

## What is here

| #   | File                                                                        | Answers your ask                                                                          | Reading time |
| --- | --------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- | ------------ |
| 1   | [`IronwoodWildsGameplay.md`](IronwoodWildsGameplay.md)                      | A brief over the game and gameplay                                                        | 10 min       |
| 2   | [`IronwoodWildsArchitecture.md`](IronwoodWildsArchitecture.md)              | The server architecture, modular, with the design patterns shown in C                     | 20 min       |
| 3   | [`IronwoodWildsCommandsAndTime.md`](IronwoodWildsCommandsAndTime.md)        | How people issue commands and how the simulation handles them — including lazy evaluation | 20 min       |
| 4   | [`IronwoodWildsProcgen.md`](IronwoodWildsProcgen.md)                        | How procedural generation is done, procedurally                                           | 10 min       |
| 5   | [`IronwoodWildsAccountsAndStorage.md`](IronwoodWildsAccountsAndStorage.md)  | Accounts, and persistent storage under schemas                                            | 10 min       |
| 6   | [`IronwoodWildsWeb.md`](IronwoodWildsWeb.md)                                | The web interface people connect through                                                  | 10 min       |
| 7   | [`IronwoodWildsPrototypeSlice.md`](IronwoodWildsPrototypeSlice.md)          | The prototype: what the slice is and how it works                                         | 10 min       |
| —   | [`maps/samworld-system.dot`](maps/samworld-system.dot) · `.svg`             | The system map, in your datasheet style                                                   | —            |
| —   | [`maps/samworld-command-flow.dot`](maps/samworld-command-flow.dot) · `.svg` | One command, and lazy time, as a flow map                                                 | —            |
| —   | [`mock/ironwood-wilds-mock.html`](mock/ironwood-wilds-mock.html) · `.png`   | What it looks like. Open it in a browser; it is clickable                                 | —            |

Short on time: read 3, then 7, then open the mock.

## The plan in ten lines

01. **One deterministic library is the game.** `samworld` does no I/O and reads
    no clock. The server, the lab and replay are three programs that drive it.
02. **The engine has no theme.** It says Family, Actor, Patron. Ironwood Wilds
    is a content pack, `samironwood` — which is what your `tech-samworld` epic
    already asks for.
03. **Commands in, events out, views on request.** Nothing else crosses the
    engine's boundary.
04. **Time is lazy, and exact.** Every completion goes in one due-queue; steady
    change is a formula. Nothing runs until something is due *and* someone
    touches the world. Looking triggers the work; it never changes the answer.
05. **Content is rows; behaviour is registered.** A new resource is three table
    rows. A new system is one module. Neither edits the core.
06. **The world lives in memory.** A journal of accepted commands plus snapshots
    makes it durable. SQL keeps identity, and answers questions afterwards.
07. **One API.** The website is a client of it with no extra powers. Polling
    with a cursor now; a stream next; WebSocket only if it earns it.
08. **The land is a function of the seed.** A pipeline of small passes, with
    gates over a thousand seeds.
09. **The Lab is the same engine with no network and no wall clock.** It is
    where you change one number about a population and see what happens — and,
    later, where bots are bred.
10. **Build the clock first.** Slice 0, "First Landing": clockwork → land → wire
    → window → the real address.

## Where this meets your existing tickets

You already cut three tickets under `tech-samworld` on 19 November. This set
lines up with them, and can serve as a draft to critique or as raw material for
the versions you write yourself.

| Ticket                                          | Asks for                                                                   | Here                                                                                   |
| ----------------------------------------------- | -------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| `60eb0b1b` — TRD, theme-independent game design | actors, families, grind economy, bots as first-class                       | `IronwoodWildsGameplay.md`, with the engine vocabulary in its §17                      |
| `9e03b041` — architecture doc + dot maps        | the boxes, what Ptah fills, what is missing                                | `IronwoodWildsArchitecture.md` §3–§4; `maps/`                                          |
| `c853eef1` — lazy time model                    | observer of record, contention, cadence, replay, determinism, hosting cost | `IronwoodWildsCommandsAndTime.md` §6 — §6.5 takes the ticket's questions one at a time |

## Decisions

### Yours — kept exactly as given

Text-only and generated · one central server for people and bots · several
patient people per player · timed actions with short queues, lazily evaluated ·
a strong tech tree and an OSRS-style grind · PvM in parties, no PvP ever ·
Ironwood Wilds, low fantasy, a homeland to supply · Favor buys people, PvM kills
them · a hold to build and defend, raids that take goods and never people ·
open-ended, with restarts.

### Mine — made so that you do not have to deliberate; override any

| Decision                                                    | Where argued                    |
| ----------------------------------------------------------- | ------------------------------- |
| Engine is `samworld`; theme is the pack `samironwood`       | Architecture §4, §6.1           |
| A due-queue, not per-entity resolve-on-observe              | CommandsAndTime §6.1–6.2, §9    |
| Single thread owns the world; a second only writes          | Architecture §7                 |
| Journal plus snapshots, not rows-as-state                   | AccountsAndStorage §3           |
| Files first, Postgres when going online                     | AccountsAndStorage §1, Slice §8 |
| REST and a cursor first; a stream before WebSocket          | Web §3                          |
| Sign in by pasting a token, at first                        | AccountsAndStorage §2.2         |
| Plain JavaScript play client; server-rendered public pages  | Web §9                          |
| Typed pools, not an ECS                                     | Architecture §6.6               |
| Dice, but keyed — no global random stream                   | CommandsAndTime §6.8            |
| No hunger or sleep; no direct trade; two-week alpha seasons | Gameplay §16                    |

**The one place I push back on your notes.** Your ticket and your Biofabrica
notes describe actions resolving *when observed*. I keep everything that idea is
for — no tick loop, no work without attention — and change one thing: the
outcome is fixed by world time, and looking only decides when the server gets
around to computing it. Otherwise whoever refreshes first wins a contested seam,
and neither replay nor fair experiments are possible.
`IronwoodWildsCommandsAndTime.md` §9 shows both versions of the same hour.

### Only you can answer — each with the default I would proceed on

| #   | Question                                                                                          | Default                                                   |
| --- | ------------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| 1   | Is "resolve on observe" a requirement in itself, or is the real goal "no work without attention"? | The goal. The due-queue delivers it                       |
| 2   | Are shared, regrowing resources acceptable under "no PvP"?                                        | Yes: your landing's nodes are yours; the wilds are shared |
| 3   | How long is a season, and does anything carry over?                                               | Two weeks in alpha; names and honours only                |
| 4   | Where does the public server live?                                                                | A small VPS behind Caddy, at the go-online step           |
| 5   | samvillage v2: build it separately, or fold its mechanics into this roadmap as content?           | Fold it in                                                |

None of these blocks the first three milestones.

## Questions worth putting to every plan, this one included

| #   | Question                                                                                                                                 | Where this plan answers              |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| 1   | Two players reach for the same resource while nobody is looking. Who gets it — and would the answer change if one of them had refreshed? | CommandsAndTime §9                   |
| 2   | How do I run it ten thousand times faster than real time, with no network, for the genetic work?                                         | Architecture §1; CommandsAndTime §11 |
| 3   | What exactly reaches disk, and what is lost on `kill -9`?                                                                                | AccountsAndStorage §3                |
| 4   | To add a resource and a way to gather it, which files change? A command? A transport? A theme?                                           | Architecture §8                      |
| 5   | What does it need that Ptah lacks, and what does it reuse?                                                                               | Architecture §3, §12                 |
| 6   | Can I change one number and compare two populations fairly?                                                                              | CommandsAndTime §6.8; Slice §5.3     |
| 7   | What can a bot see that a person cannot?                                                                                                 | Architecture §6.7                    |
| 8   | What runs at the end of the first Friday?                                                                                                | Slice §10                            |

## What was checked, and what was not

Checked while writing:

- Both maps render with `dot` without warnings.
- The mock was loaded in headless Chromium and driven through its keys, its
  options and its command line without a script error.
- The mock's miniature engine — about three hundred lines — was run under Node
  against a scripted hour of commands: 500 random observation schedules out of
  500 reached the identical world, and with no further commands the due-queue
  drained to empty. That is not the real engine. It does show the rule in
  CommandsAndTime §6.4 is cheap to keep.

Not checked — treat as estimates or illustrations:

- Every performance figure. They are orders of magnitude, to be pinned by a perf
  test.
- Every pacing number and price. They are placeholders for the lab.
- Every sample of program output. They are written by hand to show shape.
- The example region map. It is drawn to the rules, not generated.

## What I read

In `~/dev/ptah`: `AGENTS.md`, `PROJECTS.md`, and the planning documents for
samvillage, samgrognard, samvm, samgenai, samhttp, samtechtree, procgen,
samsimulator and biofabrica, plus the 18 November portfolio audit; the public
headers of samplex, samhttp, samdata, samstring, samgenai, samvillage and
samgrognard; the shape of `samvillage_action.c` and
`apps/samvillage/src/server.c`; samtechtree's migrations and its port header;
`tools/web/README.md`; `docs/dot/preferred-flow.md`; the samvillage and
samgrognard spec folders; and the three `tech-samworld` tickets.

Outside it: `~/dev/biofabrica/docs` and
`~/dev/sam-projects/ideas/automation_game`, for your earlier thinking on lazy
evaluation, the instruction format and control layers. While looking for earlier
Ironwood material I also opened two files under `~/dev/sam-projects/notes`;
nothing from them is used here.
