# Ironwood Wilds — Gameplay Brief (IronwoodWildsGameplay.md)

> Working name: **Ironwood Wilds** (domain owned). Engine lib working name:
> `samwilds`. Everything here is a design target for planning, not a spec —
> numbers are first-draft tunables, names are placeholders.
>
> Lineage: this game is Biofabrica's "player controls many units via API"
> mechanic re-settled on samvillage's loop (needs + timers + queues + production
> chains + events), taken online as samvillage TRD §1.1 phase 2/3.

## 1. What Ironwood Wilds is

A **persistent, text-first, procedurally generated world** on a central server.
Every player runs a **household** — a small group of people — sent from the Old
Country to extract resources from the Ironwood and ship them home. Players and
bots command their people over an HTTP API; the world ticks on regardless. There
is no story to finish: seasons run, worlds regenerate, and the interesting
content is **what the households do** — the emergent history of the map.

Three pillars:

1. **Emergence over narrative.** No plot, no quests from a designer. The world
   generates situations (a rich iron vein far from water; a beast pack between
   you and it); players generate stories by responding.
2. **Patience, not twitch.** People are never idle-random (no Sims autonomy): an
   uncommanded person stands fast, forever. All costs are **time costs** — real
   minutes — so play is about orchestrating several people's timers, checking
   in, re-planning. Deliberately grindy in the OSRS sense: skill levels gate
   tooling and speed, and raising them is the loop.
3. **API-first, humans and bots equal.** The web UI, any future mobile client, a
   Python script, and eventually a genetic algorithm all issue the same commands
   over the same endpoints. Watching what bots evolve into is an explicit goal,
   not a hack.

No PvP. Ever. Conflict is household-vs-wilderness (expeditions) and
household-vs-raiders (hold defense). Cooperation is allowed and lightly
mechanized (shared projects later, see §8).

## 2. The frame (deliberately open)

The **Old Country** is under creeping siege by a foe that is, unnervingly, doing
to *its* neighbours exactly what we do to the Ironwood: extracting to feed a
war. To survive, the Old Country charters **households** — small parties of
settlers — to new land across the sea. Each season, the fleet lands on a
freshly-generated stretch of the Ironwood.

The foe is never named or shown in v1. This is on purpose:

- it keeps the lore load near zero (text game, one writer),
- each season can re-skin the threat (raiders from the sea, a plague winter, the
  forest itself turning),
- the mirror ("we are somebody else's monsters") is cheaper to imply than to
  render.

Working label for the raiding enemy: **the Reavers** — renameable per season
without touching systems.

## 3. The player's unit: the household

- One account = **one household per season** (decision; see
  IronwoodWildsAccountsAndPersistence.md §2).
- A household starts with **3 adults** and lands with a standard kit (tools,
  rations, a charter banner).
- Population cap grows via **Charter points** (§6): shipping value home earns
  Charters; Charters buy **new folk** (migrants arrive by ship). Cap ~12 living
  folk per household (tunable; server load and UI sanity).
- Folk have: name, age, sex, **skills** (§5), needs (hunger/energy, light
  samvillage-style decay), inventory, and a per-person **action queue** (§4).
  Lineage eventually (samvillage §4.9 already has the machinery: pairing,
  pregnancy, surnames, inherited aptitude) — v1 buys people by Charter instead;
  births are a later, rarer channel.

You are the household's steward, not any single person. The web UI's
one-tab-per-person layout (see IronwoodWildsWebInterface.md) mirrors this: each
tab is a person, all tabs share one world feed.

## 4. Time and actions (the core loop)

- **1 tick = 1 real second.** The world runs on wall-clock, 24/7. No pause, no
  time scale (those were samvillage's offline-only affordances).
- Actions take real minutes: mining a tile ~3 min, felling a tree ~2 min,
  hauling a load home ~1-4 min (distance), a forge batch ~8 min, raising a wall
  section ~20 min, a hunting expedition ~30-90 min.
- Each person has a queue: **1 running + 8 pending** (samvillage proved the
  shape; deeper because households go offline for hours — a bedtime queue should
  survive the night).
- Actions are **lazily evaluated**: the engine stores completion timestamps in a
  heap and does O(1) work per tick regardless of how many actions are "in
  flight" (details in IronwoodWildsCommandsAndSimulation.md §5). Offline
  progression is therefore free: your folk finish their queues and then stand
  patient while you sleep.
- Validation happens **twice** — at enqueue (instant client feedback) and at
  action start (the world may have changed: the tree may be felled, the vein
  tapped out). Fail-soft, logged, queue advances. samvillage §4.4's
  rejection-code discipline carries over verbatim.

The minute-to-minute loop: open the world feed → see what finished and what
happened (events!) → re-queue 3-6 people → close tab. The week-scale loop: push
a skill past a gate → unlock a tech (§5) → new buildings/gear → deeper
expeditions (§7) → richer shipments → Charters → more folk.

## 5. Skills and the tech tree

**Skills** (per person, OSRS-flavoured grind):

| Skill                          | Grows by            | Unlocks                                   |
| ------------------------------ | ------------------- | ----------------------------------------- |
| Woodcutting, Mining, Quarrying | gathering           | tool tiers, rare nodes, speed             |
| Carpentry, Masonry, Smithing   | building/crafting   | construction tiers, tool making           |
| Fieldcraft, Cooking, Medicine  | subsistence         | party endurance, food quality, wound care |
| Hunting, Warfare               | expeditions/defense | weapon tiers, party roles, defense plans  |

- Levels 1-99, XP curve ~ OSRS-shaped (`xp(L) ∝ L²`); level affects duration
  (down) and yield/quality (up). A skill at 40 doing a 3-minute job at 20 feels
  like the reward it is.
- Cross-person cooperation: big jobs (a keep wall, a forge) accept **parallel
  work** from multiple folk — samvillage's parallel BUILD accumulation
  generalized to any construction.

**Tech tree** (per household, data-driven — see
IronwoodWildsServerArchitecture.md §7 "rules as data"):

```
Arrival      flint tools → axes, picks → rations workflow → lean-to
Settlement   log cabins → wells/palisade → smokehouse, tannery → farm plots
Industry     sawpit, forge, bloomery → tool making → kiln → deep iron
Fortification stone walls → gatehouse → watchtower → murder hole → keep
Expedition   hunting camps → sledges → wainwright → reinforced wagons
```

- Nodes unlock by: skill milestones in the household + delivered resources (a
  node "prices" itself in materials and minimum skills).
- The tree is **season-tweakable data**, not code: new seasons can add, move, or
  price nodes to shake up the meta after each reset.
- Later (post-v1): a handful of **server-wide communal projects** per season (a
  Tale-in-the-Desert-style harbour, road, or great hall that any household can
  contribute work to — Biofabrica §4's voting idea, transplanted). Gives the map
  shared landmarks and a cooperation story without PvP.

## 6. The economy loop: Charters

The only score that matters. Shipping goods home:

1. Produce surplus at the hold (ingots, planks, cured hides, herbs, relics from
   ruins).
2. **Crating**: folk pack a shipment crate (an action, costs time+materials).
3. The ship calls at the coast **every game week** (fixed schedule — the week's
   rhythm). Loaded crates leave; their **Charter value** credits the household.
4. Charters buy: **new folk** (escalating cost: 100 / 175 / 275 / …), later:
   rare seeds, breeding stock, specialist migrants.

Charter values are the master balance dial — one table (rules-as-data) relating
goods to points, tunable per season. Deaths from expeditions (§7) make Charter
income a survival need, not a vanity metric: dead folk must be replaced.

## 7. PvM: expeditions and raids

Two completely separate combat systems, both **deterministic auto-resolvers**
with no realtime input — planning is the gameplay, the report is the drama.

**Expeditions (party vs wilderness):**

- The wilderness (beyond a settled radius) holds beast packs, bandit camps, and
  ruins — placed by worldgen (IronwoodWildsProcgen.md §6).
- You form a **party** (2-6 folk), provision it (rations, gear, medicine — a
  party with bad food travels slower and fights worse), pick a target, and give
  a **standing order set** (engage / avoid / scout-only; retreat threshold as %
  wounded).
- Travel + encounter resolution play out over real tens of minutes; the server
  emits an event stream (the chronicle of the hunt). A deterministic CRT-style
  resolver (samgrognard's combat.md is the in-house precedent for legible,
  itemized results) decides each encounter.
- Risk: **folk can die or be wounded** on expeditions. Wounds heal at the hold
  over days (Medicine speeds it); death is permanent — hence Charters →
  replacements.
- Reward: hides, meat, rare materials, **relics** (high Charter value — the
  incentive to go far).

**Raids (Reavers vs the hold):**

- Every 1-2 real weeks (announced by smoke on the horizon days ahead — scheduled
  dread), a raid party hits the hold.
- Defense is **pre-planned, not played**: you assign folk to posts, set
  gates/traps/oil, choose engage-withhold per wall section. The plan is an
  artifact you iterate on between raids (a first-class data object in the
  household).
- Resolution is an automatic, replayable battle report. Losses: **stock and
  buildings, never folk** (your stated rule — raids hurt, don't grief).
  Unprotected stock is looted; breached walls burn; a well-planned palisade
  makes raids a non-event, which is its own reward.
- Under-planned holds lose stuff → grind to rebuild → the raid cadence keeps
  construction meaningful. This is the "protect your castle" pillar.

## 8. Construction

- Tile-based building on claimed land around the household banner (claim radius
  grows with household size).
- Buildings: samvillage's construct/parallel-work/decay/maintain machinery
  generalized — same lifecycle (site → work → active → worn → ruined →
  repaired), more parts (walls, gates, towers are connective geometry with
  per-part HP for raids).
- The hold is the player's canvas: layout is expressive and *mechanically
  load-bearing* (raid pathing, storage adjacency → haul times, workshop
  bonuses).

## 9. Seasons (server resets)

- A season = **~8 real weeks** on one generated world (one seed).
- End of season: the fleet leaves, the world archives (final snapshot + full
  journal retained), leaderboards freeze, a **season chronicle** renders (see
  below), and the next season opens on a fresh seed with optional rules tweaks
  (tech prices, Charter table, raid cadence, foe re-skin).
- Accounts and lifetime stats persist; households restart with the standard kit.
  Resets are diegetic (the charter system) and practical (procgen is the content
  engine; stale maps are the content killer).

## 10. Show-off surface (why this is fun to watch)

The world emits a total event stream (every action, battle, shipment, death,
arrival — see IronwoodWildsCommandsAndSimulation.md §6). That single stream
feeds:

- **Spectate mode**: a public, read-only, no-login page — the map, the event
  feed, household rankings. Zero-friction content for streams and screenshots.
- **Household chronicle**: an auto-narrated history page ("Week 3: Maren felled
  the old iron-oak; the Reavers came twice; we shipped 400 planks and lost Bram
  to the white wolf pack") — procgen text over the journal
  (planning/procgen_mvp.md's description layer is the eventual engine for this
  prose).
- **Timelapse maps**: nightly render of the world diff — forests receding, holds
  rising. Cheap, viral-form-factor content.

## 11. What is explicitly NOT in v1

PvP, markets/trading between households, births/lineage, weather systems,
seasonal crops, NPC factions beyond beasts/Reavers, LLM-driven folk. Each has a
one-line sketch in §5/§6/§8 so the design doesn't brickwall them, but v1's job
is the loop: **gather → build → skill up → ship home → defend → repeat, on a map
worth talking about.**
