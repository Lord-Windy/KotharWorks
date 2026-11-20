# Ironwood Wilds — The Prototype: Slice 0, "First Landing"

**Status:** proposal v0.1 · 2026-11-20 · author: Fable
**Scope:** the first thing to build — what is in it, what it looks like,
how one action travels through it, and the order to build it in.
**Mock:** `mock/ironwood-wilds-mock.html`.

> Every number here is a placeholder for the lab to tune. Every sample of
> program output is illustrative, written by hand to show the shape.

---

## 1. What it is

The thinnest path through **every** layer — generated land, timed actions,
a shared world, the journal, the API, the website, a bot, the lab — with
just enough game on it to hold someone for an hour.

The loop:

```
  fell timber ─▶ burn charcoal ─┐
                                ├─▶ smelt ingots ─┬─▶ forge better tools ─▶ work faster
  mine ore ─────────────────────┘                 └─▶ ship through the Gate ─▶ Favor
                                                                                 │
                                           a fourth settler ◀────────────────────┘
```

It is a complete small game: three people, a handful of places, one
decision that matters (tools first, or Favor first?), and a goal.

## 2. What it proves

| Hypothesis | How the slice tests it |
|---|---|
| Lazy time is correct when a world is shared | Two Houses on one seam; the schedule-independence test |
| One engine really serves the server, the lab and replay | A journal written by the server replays in the lab to the same hash |
| Content is rows | The whole Ironwood pack is tables; a three-row toy pack in the tests boots the same engine |
| The API is playable from outside | `samworld-bot` reaches a first recruit over HTTP |
| It survives being killed | `kill -9`, restart, same world |
| The loop holds a person for an hour | You play it in a browser |
| The lab can answer a question | One sweep: how does time-to-first-recruit move with ore regrowth? |

It deliberately does **not** test whether the full game is good. It tests
whether the machine under it is sound, and whether the smallest loop is
worth extending.

## 3. Scope

| In | Out — and where it goes |
|---|---|
| One generated shore region, about nine sites | Wild regions, tiers → slice 2 |
| Three settlers a House; skills and XP | Aptitudes → slice 1 |
| Timed actions, queues, counts, fail-soft, cancel | Standing orders → later |
| Gather, craft, transfer, travel, ship | Build, study → slice 1 |
| Shared seams that regrow | — |
| Tools that speed work | Tool wear → slice 1 |
| Shipping for Favor; recruiting | Demand shifts, war effort → slice 3 |
| Several Houses in one world | Combat, parties, death → slice 2; raids → slice 4 |
| Journal, snapshots, recovery; file storage | Postgres → the go-online step of this slice |
| REST with a cursor; token sign-in | The stream → slice 5 |
| The play client; one public page | The observatory proper → slice 3 |
| A scripted bot, in the lab and over HTTP | Evolved policies → slice 5 |

## 4. Content

**Goods**

| Key | Name | Weight | Notes |
|---|---|---|---|
| `log` | ironwood log | 2 | |
| `stone` | stone | 3 | no use yet; proves a dead-end good is harmless |
| `iron_ore` | iron ore | 3 | |
| `charcoal` | charcoal | 1 | |
| `iron_ingot` | iron ingot | 2 | ships for Favor |
| `stone_axe`, `stone_pick` | stone tools | 2 | starting kit |
| `iron_axe`, `iron_pick` | iron tools | 2 | work takes 70 % as long |

**Skills:** woodcutting, mining, smithing. Each level shortens that work by
2 %. XP per level doubles about every seven levels.

**Actions**

| `do` | Mechanic | Where | Takes | Gives | Time | XP | Needs |
|---|---|---|---|---|---|---|---|
| `travel` | travel | — | — | arrival | the route | — | — |
| `chop` | gather | a stand | one from the stand | 1 log | 20 s | 25 woodcutting | an axe |
| `quarry` | gather | an outcrop | one | 1 stone | 25 s | 20 mining | a pick |
| `mine` | gather | a seam | one | 1 iron ore | 30 s | 25 mining | a pick |
| `deposit`, `withdraw` | transfer | a store | goods | goods | 10 s | — | — |
| `burn` | craft | the kiln | 2 logs | 1 charcoal | 60 s | 30 smithing | — |
| `smelt` | craft | the bloomery | 2 ore, 1 charcoal | 1 ingot | 90 s | 60 smithing | Smithing 3 |
| `forge_axe`, `forge_pick` | craft | the bloomery | 2 ingots, 1 log | an iron tool | 180 s | 120 smithing | Smithing 8 |
| `ship` | ship | the Gate | goods | Favor | 30 s | — | — |
| `wait` | wait | — | — | — | as asked | — | — |

Ten actions, six mechanics. Every row above is data in `samironwood`.

**The land:** the region in `IronwoodWildsProcgen.md` §7 — a landing with a
storehouse, kiln, bloomery and the Gate; two stands of ironwood; an
outcrop; a ford; a seam fifteen minutes away across the river.

**Constants:** pack holds 30 weight · queue 1 + 5 · count up to 50 · an
ingot ships for 5 Favor · the fourth settler costs 100 Favor, each one
after that half as much again.

**Pacing check:** a first recruit needs twenty ingots — forty ore, forty
logs, and a lot of walking. Roughly an hour and a quarter for three
settlers at real speed; thirteen minutes with `--time-scale 6`; well under
a second in the lab.

## 5. What it looks like

### 5.1 By hand

```console
$ curl -s $IW/v1/register \
    -d '{"handle":"sam","house":"Ashdown","kind":"human","invite":"FIRST-LANDING"}'
{"ok":true,"family":"f-1","token":"iw_api_7k2m_Q4ZT…","note":"Shown once. Keep it."}

$ T="Authorization: Bearer iw_api_7k2m_Q4ZT…"
$ curl -s -H "$T" $IW/v1/family | jq -c '.actors[] | {id,name,state,site}'
{"id":"a-1","name":"Brynn","state":"idle","site":"s-0.0.0"}
{"id":"a-2","name":"Odo","state":"idle","site":"s-0.0.0"}
{"id":"a-3","name":"Hale","state":"idle","site":"s-0.0.0"}

$ curl -s -H "$T" $IW/v1/actors/a-1/options | jq -c '.options[]'
{"do":"travel","to":"s-0.0.1","name":"Hollin Stand","ms":240000}
{"do":"travel","to":"s-0.0.4","name":"Crag's Foot","ms":360000}
{"do":"burn","at":"b-2","ok":false,"why":"NO_INPUTS","needs":{"log":2}}

$ curl -s -H "$T" $IW/v1/commands -d '{"op":"queue.append","actor":"a-1","args":{"actions":[
      {"do":"chop","at":"n-0.0.1.0","until":"full"},
      {"do":"deposit","into":"b-1","what":"all"}]}}'
{"ok":true,"seq":4,"now":61000,"rev":3,
 "result":{"queued":4,"inserted":["travel","travel"],"first_ends_at":301000}}
```

Twenty minutes later:

```console
$ curl -s -H "$T" "$IW/v1/events?since=0" | jq -r '.events[].text' | tail -4
Brynn Ashdown fells an ironwood and trims the log.
Brynn Ashdown's pack is full.
Brynn Ashdown walks back to Ashdown Landing.
Brynn Ashdown stacks 15 logs in the storehouse.
```

### 5.2 In a browser

The screen in `IronwoodWildsWeb.md` §6.2, and the clickable mock. A session
is: press `n`, read where that settler is, press a letter or two to give
them a queue, press `n` again. When nobody is idle, watch the log or close
the tab.

### 5.3 In the lab

```console
$ samworld-lab run --seed 0x1A0 --families 100 --policy greedy-v0 --days 30 --out out/
world    seed=0x1a0  pack=ironwood@1  rules=9f2c41…  families=100  actors=300
ran      30 world days in 0.84 s    1.9M due events    212k commands
first recruit    median 1h14m    p10 0h58m    p90 1h41m
favor / family   median 2,310    max 4,105
wrote    out/journal/000001.jsonl    out/snapshots/0000212004.swb    hash 5be0…c1
```

```console
$ samworld-lab sweep --vary node.iron_seam.regrow_per_hour=3,6,12 \
                     --seeds 30 --families 20 --days 10 --policy greedy-v0
regrow/h    first recruit (median)    favor/family (median)    idle share
    3            1h52m  ±6m                 610  ±40               31 %
    6            1h14m  ±4m                 905  ±35               18 %
   12            1h09m  ±3m                 960  ±30               15 %
paired on keyed rolls: runs differ only where the change reaches
```

That second command is the statistics-course idea, working: change one
number about the world, run a population thirty times, read the answer.

## 6. How it works: one lump of ore through every layer

Brynn is at the landing. Her owner types `mine redcap x10`.

| When | Layer | What happens |
|---|---|---|
| enter | Web client | Resolves *redcap* to the seam's node, builds `{"op":"queue.append","actor":"a-1","args":{"actions":[{"do":"mine","at":"n-0.0.6.0","count":10}]}}` and POSTs it |
| +1 ms | Caddy → samhttp | TLS ends; the request is parsed and routed |
| | `api.c` | Token → account → Family `f-1`; rate limit passes |
| | `wire.c` | JSON becomes a `SamWorldCommand`, checked against the arg spec |
| | `sim.c` | `samworld_advance_to(now)` settles anything due |
| | Engine: `check` | Brynn is alive and Ashdown's; the seam is known; the queue has room. Nothing has been changed yet |
| | Engine: `apply` | Brynn is elsewhere, so a `travel` goes in first, then the `mine`. She was idle, so the driver starts the `travel`: its end, fifteen minutes on, goes into the due-queue |
| | `sim.c` | The command record goes to the writer thread; within 100 ms it is on disk |
| +2 ms | Web client | The reply arrives with the new `rev`. The rail shows *Brynn — walking — 15:00* and starts counting down on its own |
| … | Server | **Nothing.** Nobody is asking; nothing is running |
| 15:00 | Engine | Still nothing happens *at* fifteen minutes. The next time anyone touches the world, `advance_to` pops the due entry: Brynn arrives; the driver starts `mine`, unit 1; the gather mechanic takes one lump from the seam; a new due entry, thirty seconds on |
| 15:30 | Engine | Popped likewise: +1 iron ore, +25 mining XP; an `action.done` event stamped 15:30 exactly; unit 2 starts |
| next poll | Web client | `GET /v1/events?since=…` returns the event with its sentence already written. The log prints *Brynn Ashdown pries a lump of iron ore from the seam.* |

The client never asked "is she done yet?". It knew when she would be, and
asked afterwards what had happened.

## 7. The bars

The slice is done when all of these hold.

| # | Bar | The test |
|---|---|---|
| B1 | **Deterministic** | The golden hash; the same script under a thousand random schedules reaches one hash |
| B2 | **Durable** | `kill -9` mid-run, restart: the world's hash equals the lab's replay of the same journal |
| B3 | **Playable** | A person registers in the browser, runs three settlers and recruits a fourth, never reloading the page; `n` always finds the idle one |
| B4 | **Bot-playable** | `samworld-bot` reaches a first recruit over HTTP against a live server at time scale 60 — a CTest, like `samvillage_bot_soak` |
| B5 | **Fast** | A hundred Houses for thirty world days, headless, in under ten seconds, twice, identically. One sweep prints its table |
| B6 | **Theme-independent** | A three-row toy pack boots the same engine |
| B7 | **Baseline frozen** | `greedy-v0` is kept verbatim. Slice 1 must make it fail — the samvillage v2 bar, reused |

## 8. Milestones

Each ends in something you can run and show. Stopping after any one of
them still leaves something whole.

| # | Name | Builds | The demo |
|---|---|---|---|
| M1 | **Clockwork** | `samtime`; the `samdata` additions; the engine core with gather, craft and transfer on a hand-made three-site world; the determinism suite | A test prints the two-Houses-one-seam timeline, and a thousand shuffled schedules agree |
| M2 | **The Land** | `samprocgen`; the `samironwood` pack; the region generator; travel; options; text; the patron loop; the lab's `worldgen` and `run` | An ASCII map from a seed. Twenty bot Houses racing to a first recruit, in the terminal, at a thousand times speed |
| M3 | **The Wire** | `samjournal`; the server — views, commands, feed, tokens; the HTTP bot; recovery | The `curl` session above. The bot playing a live server. Kill it; it comes back |
| M4 | **The Window** | The play client; one public page; the lab's `sweep` and `fork` | Play it in a browser |
| M5 | **The Gate Opens** | Postgres adapters; invites; Caddy, systemd, the domain; backups | Friends and bots on the real address |

## 9. Ticket outline

Not created — a proposal for when you are ready. Label: your existing
`tech-samworld`, plus topic labels.

| # | Ticket | After | M |
|---|---|---|---|
| 1 | `samdata`: `samrng_keyed`; SHA-256 lifted from samgrognard | — | 1 |
| 2 | `samtime`: time type, clock mapping, due-queue, flows; perf test | 1 | 1 |
| 3 | `samworld` skeleton: rules builder, registry, module list, world, pools | 2 | 1 |
| 4 | `samworld` core: commands, the queue driver, inventory, skills, events | 3 | 1 |
| 5 | Mechanics on a hand-made world: gather, craft, transfer, wait; the toy pack | 4 | 1 |
| 6 | Determinism suite: world hash, golden and `--bless`, schedule test, architecture test | 5 | 1 |
| 7 | `samprocgen`: fields, sampling, weighted tables, name grammar, text helpers | 1 | 2 |
| 8 | `samironwood` v0: the spec folder and its tables; golden numbers | 5, 7 | 2 |
| 9 | The land: region passes 0–8, thin; travel and explore; gates G1, G2, G7, G8 | 8 | 2 |
| 10 | Patron module: ship, Favor, recruit | 5 | 2 |
| 11 | Views, options, and event text | 9 | 2 |
| 12 | `samworld-lab`: `worldgen`, `run`, the in-process host, `greedy-v0`, metrics | 10, 11 | 2 |
| 13 | `samjournal`: records, canonical JSON, chain, snapshot container, file sink, verify | 1 | 3 |
| 14 | Snapshot and restore; the clone test; lab `replay` and `verify` | 6, 13 | 3 |
| 15 | `samworld-server`: sim loop, clock, housekeeping, views, commands, feed, wire codec; API test | 11, 14 | 3 |
| 16 | Accounts on files: register, tokens, rate limits, idempotency | 15 | 3 |
| 17 | `samworld-bot`: the HTTP host; bot soak; the kill-and-recover test | 12, 16 | 3 |
| 18 | The play client; served from `--web-root`; Playwright smoke | 16 | 4 |
| 19 | One public page: world summary, ladder, ticker | 15 | 4 |
| 20 | Lab `sweep` and `fork` | 12, 14 | 4 |
| 21 | Postgres adapters; migrations; the role file | 16 | 5 |
| 22 | Deployment: `servers.conf` stanza, Caddy config, env file, backup script; `PROJECTS.md` entries | 18, 21 | 5 |

## 10. The Fridays

Today is 20 November. Before Christmas there are four more: 27 November, 4,
11 and 18 December.

| By | A reasonable target | Why it is the right place to stand |
|---|---|---|
| 27 Nov | **M1** | The clock is the foundation. Everything else leans on it |
| 4 and 11 Dec | **M2** | The first thing worth watching: bots racing on generated land |
| 18 Dec | **M3** | A persistent world a bot can play over HTTP — the whole spine exists |
| January | **M4**, then **M5** | A browser, then the real address |

That pacing assumes tickets like the ones above, written tightly enough to
hand to an agent and review after — the way samvillage and samgrognard
were built. M4 before Christmas is a stretch, not a plan.

**The first Friday.** Do not start with the server or the website. Start
with the clock: tickets 1–6. By the end of the day a test should print the
seam timeline from `IronwoodWildsCommandsAndTime.md` §9, and a second test
should show a thousand shuffled schedules agreeing on it. Once that is
true, nothing built on top can quietly be wrong about time.

## 11. After the slice

| Slice | Adds | Its bar |
|---|---|---|
| **1 — Hearth** | The build module: structures, housing, hold tiers. The tech module: study and unlocks. Tool wear. Aptitudes | `greedy-v0` fails; a planning policy passes |
| **2 — Teeth** | Combat: lairs, parties, tactics, timed rounds, loot, death. Wild regions | A party can lose someone; a piece of loot gates a recipe |
| **3 — The War** | Demand shifts, the war effort, a season from opening to archive, the hall of fame, the chronicle, the observatory | A season opens, closes and replays |
| **4 — Walls** | Raids: threat, warning, defence plans | Holds lose goods and never people |
| **5 — Minds** | An evolved policy through `samvm` and `samgenai` in the lab. The stream. Ladders by league | A bred policy beats `greedy-v0` |

Slice 5 needs real genetics — the six staged `tech-samgenai` tickets. They
can proceed in parallel; nothing before slice 5 waits on them.

## 12. Risks particular to the slice

| Risk | Answer |
|---|---|
| The loop is not fun | Learn it at M4, cheaply. Tune in the lab; that is what it is for |
| Scope creeps toward building and tech | B7 says no: the slice ends when a greedy bot can win |
| The web client swallows the Fridays | A line budget; the mock exists first; the server describes, the client only draws |
| The generator becomes a hobby | The slice needs one region and four gates |
| Five libraries feel like five projects | Each milestone's demo crosses all of them. Nothing is built that the demo does not run |
