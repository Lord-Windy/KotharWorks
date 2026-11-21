# Ironwood Wilds — Gameplay Brief

> **Status:** planning brief for review. No code has been written against this
> document. Numbers are tunable defaults (rules-as-data, see
> [IronwoodWildsArchitecture.md](IronwoodWildsArchitecture.md) §6); their
> *names and semantics* are the spec.
>
> **Companion docs:** [README.md](README.md) (index) ·
> [Architecture](IronwoodWildsArchitecture.md) ·
> [Commands](IronwoodWildsCommands.md) ·
> [Procgen](IronwoodWildsProcgen.md) ·
> [Web](IronwoodWildsWebInterface.md) ·
> [Persistence](IronwoodWildsPersistence.md) ·
> [Prototype](IronwoodWildsPrototype.md)

## 1. What Ironwood Wilds is

**Ironwood Wilds is a slow, text-based, procedurally generated colonisation
sim played over an HTTP API.** Each player runs a **household** — a small
roster of patient colonists — deposited on a fresh, hostile world. You queue
timed actions for each colonist (mine, chop, build, scout, fight), walk away,
come back, and ship resources home to a besieged homeland. Everything of
consequence is text: descriptions, event logs, a chronicle wire, an ASCII
map. The world runs whether you watch it or not.

Three sentences of fantasy:

> The Homeland is losing a long war. To survive it, the Homeland sends
> households like yours across the water to wild worlds — to dig, fell,
> forge, and ship it all back. Someone else was sent too. Someone else's
> expedition is out here doing exactly what you are doing, to keep exactly
> what you are keeping. Nobody will tell you who they are.

The mystery is **deliberately unresolved**. Server restarts ("seasons",
§9) re-roll the world, the enemy's shape, and what the rumors mean. The
premise is a frame for emergence, not a plot to be finished.

### 1.1 Design pillars

| # | Pillar | Meaning in practice |
|---|--------|---------------------|
| P1 | **Patient characters** | Colonists never act on their own. No autonomy, no idle fidgeting. An empty queue means the character stands still, forever, until told otherwise. (The explicit anti-Sims rule.) |
| P2 | **Multi-character orchestration** | The player is a household, not a hero. The core skill is scheduling several people across queues — like a foreman cycling clipboard tabs. |
| P3 | **Time is real** | Actions take minutes of real time and resolve on the server whether you are logged in or not. Logging off is a strategy, not a pause. |
| P4 | **Grind is honest** | OSRS-style skill XP and a household tech tree. Progress is legible, numerically stable, and never purchasable. The grind *is* the content, so it must feel fair and be scriptable by bots. |
| P5 | **PvM, never PvP** | The only violence is against the world and the enemy. Players cannot harm players. Cooperation is ambient (shared chronicle, shared region events), never mandatory. |
| P6 | **Loss has shape** | Expeditions can kill colonists permanently (season-permanent). Raids on your hold cost goods, never people. Both losses are recoverable through play, not payment. |
| P7 | **Watchability** | The world must produce stories on its own: a chronicle wire, per-character journals, spectate endpoints, deterministic replays. Built to be shown to other people, not just played. |
| P8 | **The API is the game** | Humans and bots hit the same REST + SSE protocol. If a bot can't play it, it isn't finished. (This is also the on-ramp for genetic algorithms later — see §11.) |

### 1.2 Inspirations and what is borrowed

| Inspiration | Borrowed |
|---|---|
| Dwarf Fortress | Procedural worlds that generate stories; layered text detail (look → examine); seasons of history |
| OSRS / RuneScape | Skill XP curves, long grinds, gated content, the *feel* of levelling; timers as gameplay |
| The Sims | Multi-character household as the player unit — consciously **not** borrowing autonomy |
| SpaceTraders | API-first play, timed actions, queues, bots as first-class citizens |
| A Tale in the Desert | Cooperative mega-projects (season-end possibility, §9); hand-scale production |
| samvillage (olderplanning) | Two-phase action validation, fail-soft queues, goal board, rules-as-data, lineage/births |
| biofabrica (olderplanning) | Weekly-reset discipline; awareness/escalation dial; PvM-as-autobattler; no direct trading |

## 2. The player unit: household

- One **account** = one **household** per season (deliberate: keeps the
  roster the emotional unit and the API surface small).
- A household starts each season with **4 adult colonists** (a mix of starting
  skills), a **landing camp**, and a modest crate of supplies.
- **Roster growth** has two valves, both earned in-play:
  1. **Favor** — spend points gained by shipping goods home (§7) to recruit a
     new colonist from the Homeland (arrives on the next ship).
  2. **Births** — paired colonists with a bed to spare can have children who
     mature into controllable adults (lineage rules inherited from samvillage
     §4.9: surnames, inherited aptitude). Children are roster slots, not
     micro-units.
- **Roster cap** = beds + Favor tier, soft-capped at **12** for season one.
- Colonists have **needs** (food, rest, warmth in winter) but needs are
  *modifiers*, not death spirals: a hungry colonist works slower and yields
  less; only **violence kills** (expeditions; see §6). This keeps the
  walk-away loop (P3) safe while preserving planning pressure.

### 2.1 Colonist state (player-visible)

| Field | Notes |
|---|---|
| Identity | Given name, surname (household or born-in), age, lineage links |
| Skills | ~10 skills, level 1–50 season one (§4) |
| Needs | Satiety, energy, morale — 0–100, decay/recover via actions |
| Health | 100 → wounds debuff → 0 = dead (only via violence) |
| Position | Region + tile; inside a building, on an expedition, or at large |
| Plan | 1 running + up to 4 pending actions (§5) |
| Journal | Auto-generated log of what they have done (P7) |

## 3. The world

- The world is an **unbounded grid of regions** (48×48 tiles each), generated
  lazily and deterministically from the season seed (see
  [Procgen doc](IronwoodWildsProcgen.md)). Only visited regions exist; the
  fog is literally unmaterialised possibility.
- Regions contain: terrain, biomes, **resource deposits** (iron, clay, old
  forests, the signature **ironwood groves** — rare, dense, valuable),
  **sites** (ruins, caches, watchtowers, springs), and **lairs** (PvM).
- The household **claims land** at its landing; the claim and its buildings
  are the **hold** (§8).
- Off the hold, the world is common: anyone's expedition can walk the same
  wilds, see the same sites, and contest nothing directly (PvP-free
  contention: deposits deplete and regen per region, lairs are instanced per
  expedition party — no kill-stealing, no griefing surface).

## 4. Skills and XP

- Ten launch skills: **Woodcutting, Mining, Foraging, Hunting, Carpentry,
  Masonry, Smithing, Husbandry, Scouting, Arms.**
- OSRS-shaped exponential XP table, level 1–50 for season one. Exact curve is
  a `XpCurve` strategy (Architecture §5) — tunable, not lore.
- Effects of level: action **duration shrinks** (~0.8% per level), **yield
  improves**, and certain actions/loads unlock at thresholds ("ironwood
  felling requires Woodcutting 25").
- XP is granted on **completion** only (failures teach nothing — samvillage
  rule, keeps bot scripts honest).

## 5. Actions, time, and queues

- **1 tick = 1 logical second.** All durations are integer ticks. An action's
  wall-clock feel: forage 50s, chop 60–100s, mine 90–150s, build stages
  300–900s, cross a region 600–1,800s, expedition 1–3 hours.
- Each colonist has **1 running + 4 pending** actions (the samvillage D6 cap,
  kept: short queues force the satisfying cycle of returning to each tab).
- **Two-phase validation** (inherited): cheap checks at enqueue (immediate
  rejection with a code), full world checks at start time (fail-soft: log an
  `ActionFailed` event, queue advances — the bot copes, like a SpaceTraders
  bot copes with a failed jump).
- **Lazy evaluation**: the server materialises outcomes on demand — the
  completion of "chop for 60s" scheduled at t+60 is computed when the world
  needs to know, not by ticking every colonist every second. Details and
  determinism rules in [Commands](IronwoodWildsCommands.md) §3 and
  [Architecture](IronwoodWildsArchitecture.md) §4.
- **Offline progress is free**: because time is logical and lazily evaluated,
  a colonist mid-queue progresses identically whether you are watching. No
  catch-up math, no "offline mode" — there is only the world.

## 6. PvM: expeditions and the autobattler

- Monsters live in **lairs** (static, discovered by scouting) and **wild
  encounters** (roaming, seeded per region, scaled by an awareness dial, §8).
- A player forms a **party** (1–5 colonists), assigns a **loadout** (gear
  crafted up the tech tree) and a **standing order** — a small tactical plan
  (who holds the front, who flanks, when to retreat at X% health). This is
  the biofabrica insight: **PvM as a plannable autobattler**, not a reflex
  game.
- Dispatching an expedition is an action; the party travels, fights, and
  returns on the world's clock. **Resolution is deterministic** given party,
  loadout, standing order, and seeded lair state — which makes standing
  orders *optimisable* (§11 again).
- Outcomes: loot, XP (Arms/Scouting), **wounds** (heal with rest and herbs),
  and **death**. Death is season-permanent; the replenishment valve is Favor
  (§7). This is the intended sting of the game: the grind has a price.

## 7. Economy: the Tithe, Favor, and the ship

- **No direct player-to-player trading** (biofabrica rule, v1). The only
  market is the Homeland.
- The household builds and maintains a **landing beacon**; every **3 game
  days** a ship lands, takes whatever you have staged in the beacon
  stockpile, and converts it to **Favor** at a season rate table (iron >
  timber > charcoal > food; ironwood absurdly above all).
- **Favor spends on**: colonist recruitment (the death-replenishment valve),
  tech blueprints (§ tech tree), and season-end legacy boons (§9). A
  household points ladder per season feeds the leaderboard and the chronicle
  (P7).
- Storage, decay, and maintenance pressure (samvillage §4.7 lineage): goods
  rot/dampen, buildings decay, everything needs hands. Stockpiling is a
  decision, not a default.

## 8. The hold, raids, and awareness

- **The hold** is the household's claim: hall, walls, gate, towers,
  workshops, beacon, beds, storehouses — built from the tech tree, maintained
  by colonists.
- **Raids** are scheduled world events, **foreshadowed by rumor** in the
  chronicle ("smoke seen past the ridge, two nights out") — you get warning,
  and the warning is content.
- The player writes a **defense plan** (who mans which wall, where the traps
  are, fallback keep) — another plannable, optimisable data structure. At the
  raid tick, resolution is a deterministic battle sim over plan + walls +
  raid severity.
- **Raid rule (P6): raids cost goods and structures, never colonists.**
  Raided colonists end up wounded, never dead. The death valve stays with
  expeditions, where the player chose the risk.
- **Awareness** (biofabrica §6.1, ported): raid severity scales with the
  household's own activity (shipping, forging, felling ironwood raises *your*
  profile) and the region's collective activity. Push hard for the ladder →
  get hit harder; keep your head down → grow slowly and safely. The
  newcomer's raids are always gentle because awareness is personal-first.

## 9. Seasons (server restarts)

- A season runs **~6 weeks** on one seed with one ruleset version.
- A season ends by calendar or by narrative climax (the enemy's final push —
  shape varies by seed; see [Procgen](IronwoodWildsProcgen.md) §7).
- At season end the world is **archived**: chronicle frozen and exported,
  leaderboards recorded, replays retained; households receive **legacy**
  entries (favor earned, monuments built, colonists lost, one line of
  epitaph text).
- **Legacy carries forward cosmetically and mildly**: a household crest
  flourish, a starting-banner option, an extra name in the birth pool.
  Deliberately *not* power — every season's ladder must be winnable by a
  fresh household.
- Rules-as-data may change between seasons (rates, caps, even new skills);
  within a season, rules are frozen and versioned.

## 10. Non-goals (v1, written down so they don't drift in)

| Non-goal | Why |
|---|---|
| PvP of any kind | P5. There is no surface for it, by design. |
| Colonist autonomy / "life simulation" needs AI | P1. The player is the only planner. Needs are modifiers, not agents. |
| Direct trading / player markets | Keeps the economy one-dimensional and grind-honest; revisit post-v1. |
| Graphics beyond ASCII/text panes | Text is the aesthetic and the bandwidth. A canvas tile map is the only sanctioned indulgence, later. |
| Real-time reflex anything | All conflict is plannable resolution (§6, §8). |
| Voice/chat between players | v1 has no player-to-player channels; the chronicle is the only shared voice. (Moderation cost: zero.) |
| Single-player avatar mode | The household *is* the unit. |

## 11. The long game: bots, emergence, and genetics

This brief is for the *simulation first* (per the stated plan), but three
decisions above are load-bearing for later AI work, recorded so they aren't
accidentally designed away:

1. **The API is the game (P8)** — any future genetic algorithm plays through
   the same protocol as a human, so evolved brains are immediately deployable
   against the live world.
2. **Determinism everywhere** (Architecture §7) — a headless fast-forward of
   a whole season is a fitness function. Same seed + same command stream =
   same world, so `household fitness = f(replay)` is cheap and reproducible.
3. **Standing orders and defense plans are data, not reflexes** — the
   *evolvable genotype* candidates are exactly these structures (and bot
   scheduling policies). The samgenai environment seam
   (`reset/observe/act/reward`) maps 1:1 onto a household: reset = new
   season seed, observe = fogged API reads, act = command submission,
   reward = Favor delta per episode.

## 12. Decision digest (gameplay-level)

| # | Decision | Where |
|---|---|---|
| GP-D1 | Text-first, API-first, household-as-unit | §1 |
| GP-D2 | Characters are patient executors; zero autonomy | §1.1 P1, §10 |
| GP-D3 | Needs are modifiers; only violence kills; death is season-permanent | §2.1, §6 |
| GP-D4 | 1 tick = 1 logical second; 1 running + 4 pending queue cap | §5 |
| GP-D5 | Lazy, deterministic time evaluation; offline progress free | §5 |
| GP-D6 | PvM as plannable autobattler (loadouts + standing orders) | §6 |
| GP-D7 | Favor economy via the Tithe ship; no player trading | §7 |
| GP-D8 | Raids: foreshadowed, defense-planned, goods-not-people losses | §8 |
| GP-D9 | Awareness dial (personal + regional) scales raid severity | §8 |
| GP-D10 | ~6-week seasons; legacy is cosmetic-mild; rules frozen in-season | §9 |
| GP-D11 | Ten skills, level 1–50, OSRS-style XP; XP on completion only | §4 |
| GP-D12 | World = unbounded lazily-generated regions; holds are claimed land | §3 |
