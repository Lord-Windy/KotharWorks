# Ironwood Wilds — Game & Gameplay Brief

**Genre:** Persistent Online Text-Based Colony, Skill-Grind & Expedition
Simulation\
**Tone & Setting:** Quasi-Low Fantasy (gritty medieval frontier, *Game of
Thrones* / *The First Law* groundedness with rare, uncanny ancient wilds)\
**Play Modes:** Multi-tab Browser UI (Humans), REST / Socket API (External
Bots), Headless / Live Evolution (`samgenai` Genetic Programs)\
**Combat Stance:** Strictly Cooperative / Solo **PvM** + Asynchronous **Hold
Raid Defense** (Zero PvP)

______________________________________________________________________

## 1. Premise: The Charter & The Besieged Homeland

Across the Narrow Reach, the **Homeland** is losing a grinding war of attrition
against an unseen rival empire — an enemy rumored to be stripping distant
continents bare to fuel its own war machine.

You do not play a single chosen hero. You hold a **Royal Charter** as the
steward of a minor **Household** (initially 3–4 retainers, craftsmen, and
kinfolk) landed on the untamed coast of the **Ironwood Wilds**: a cold,
mist-choked frontier of bog-iron fens, slate crags, ancient iron-barked forests
whose timber turns axe-blades, and barrow-hills where things from an older age
still stir.

Your mandate has two intertwined halves:

1. **Carve out a fortified Hold** capable of surviving the frontier's beasts,
   winters, and raiding warbands.
2. **Ship Tribute back to the Homeland** — timber, smelted iron, weapons,
   provisions, and recovered relics — earning **Charter Favor** to requisition
   fresh settlers and sustain your Household against the inevitable casualties
   of deep-wild expeditions.

### Why This Premise Solves Server Restarts & Experiments

Every server lifecycle is canonically a **Charter Season** (e.g., *"The Third
Autumn Charter"* or *"Experiment #14: The Iron Famine"*):

- When the server resets — whether for an architectural upgrade, a new world
  seed, or a controlled population experiment — the Homeland recalls or closes
  the current Charter, records every Household's total Tribute and Hold tier
  into the permanent **Chronicle (`wilds_meta`)**, and dispatches fresh
  expeditions to a newly charted region.
- Wipes feel thematic rather than destructive, and seasonal modifiers ("The
  Crown demands triple steel; bog-iron seams are half as rich") double as your
  **scientific hypothesis-testing knobs**.

______________________________________________________________________

## 2. Core Gameplay Loop: "Patient Sims" Plate-Spinning

Ironwood Wilds deliberately blends **Old School RuneScape's** meditative skill
progression and **Dwarf Fortress / Sims** multi-character management — **minus
autonomous AI chaos**:

- **Multiple Independent Characters:** A Household controls a roster of distinct
  individuals (starting at 3, scaling to ~8–15+ as Hold housing and Charter
  Favor grow).
- **Zero Idle Autonomy ("Patient Executors"):** Characters never wander off to
  bake a cake while the gate is burning. When a character's action queue
  empties, they stand `IDLE` at their current location, safe (unless in an
  active field encounter), waiting patiently for orders.
- **Real-Time Timed Actions & Short Queues:** Every meaningful action takes real
  wall-clock time — from 20 seconds (chopping soft pine) to several minutes
  (mining deep iron ore, trekking two leagues inland, smelting bloomery steel).
  Each character holds **1 active action + up to 4 queued actions** (with batch
  counts, e.g., `MINE_ORE × 5`).
- **The Tab-Cycling Rhythm:** On the web interface, each character is a
  browser-style tab (`[Aldric · Mining 02:14]`, `[Brenn · IDLE ●]`,
  `[Maren · Smelting 00:41]`, `[The Hold]`). Human gameplay is the satisfying
  cadence of checking in, clearing `[IDLE ●]` badges, rotating tired or
  full-pack gatherers home, queuing the next 10–15 minutes of production chains,
  and dispatching a geared squad into a monster lair. For a bot or genetic
  program, it is a pure multi-agent scheduling and specialization optimization
  problem.

```
   ┌──────────────────┐      raw timber, bog ore, hides      ┌──────────────────┐
   │  WILDERNESS      │ ───────────────────────────────────► │  THE HOLD        │
   │  Survey · Gather │                                      │  Refine · Craft  │
   │  Delve (PvM)     │ ◄─────────────────────────────────── │  Build · Tech Up │
   └────────┬─────────┘      tools, armor, rations, parties  └────────┬─────────┘
            │                                                         │
     Field Permadeath                                          Tribute Shipments
    (risk veteran skills                                       (earn Charter Favor
     for rare relics/ores)                                      & survive Raids)
            │                                                         ▼
            │                                                ┌──────────────────┐
            └───────────── recruits replenish losses ─────── │  THE HOMELAND    │
                                                             │  Favor → People  │
                                                             └──────────────────┘
```

______________________________________________________________________

## 3. Characters, Skills & Permadeath Asymmetry

The central tension of Ironwood Wilds comes from splitting progression into
**mortal people** vs. **immortal infrastructure**:

### 3.1 Individual Characters (Mortal)

Each character has:

- **Identity & Traits:** Procedurally generated name, background, and 1–2 subtle
  aptitude traits (e.g., *Deep-Lunged*: −5% mining duration; *Steady-Handed*:
  +5% shield block).
- **Vitals & Carry Load:** Health, Stamina (drained by heavy labor/travel;
  restored by resting at the Hold or campfires + rations), Wounds (slow-healing
  combat injuries), and Pack Capacity (weight/slots — hauling matters!).
- **Equipment Slots:** Main Hand (tool or weapon), Off-Hand (shield, lantern,
  buckler), Armor (gambeson, brigandine, mail), Cloak/Pack.
- **OSRS-Style Individual Skills:** Exponential XP curve (Levels 1–99, where
  Level 50 is a respectable veteran and Level 80+ is a prized master). Higher
  skill unlocks higher-tier resources/recipes, reduces action duration, and
  improves yield/combat accuracy:

| Category           | Skills                                                                             | What Raising It Unlocks                                                                                                        |
| ------------------ | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **Extraction**     | **Woodcutting**, **Mining**, **Quarrying**, **Foraging**                           | Pine → Ash → Ironwood timber; Peat/Bog-Iron → Copper/Tin → Deep Vein Iron; Slate → Granite; Medicinal herbs & trail provisions |
| **Artisan**        | **Carpentry**, **Masonry**, **Smithing**, **Tailoring/Leather**, **Cooking**       | Sawing planks, stone ashlar blocks, bloomery iron → crucible steel, gambesons & packs, preserved rations & poultices           |
| **Frontier & War** | **Wayfaring** (Scout/Survey), **Vanguard** (Melee/Shield), **Ranging** (Bow/Spear) | Faster overland travel, revealing hidden sites/lairs, party frontline survival, ranged volleys & ambush mitigation             |

### 3.2 Why Asymmetry Makes It Sing

- **Your Household Tech Tree and Hold Buildings are permanent** (for the Charter
  Season).
- **Your Characters' Skill XP is mortal.** If your Level 62 Master Smith stays
  safely inside the Hold forging blades, they will never die — even during a
  Hold Raid! Move them into the deep wilds to mine a rare meteoric seam guarded
  by Barrow-Wights, and a botched expedition can kill them permanently, forcing
  you to train an apprentice back up inside your already-built Master Forge.

______________________________________________________________________

## 4. Tech Tree & Production Chains

Progression is anchored by a **Household Tech Tree** unlocked by investing
refined materials, research actions at a Hold Study/Drafting Table, and
recovered frontier curiosities:

1. **Tier I — Camp & Mud:** Lean-tos, Campfire, Crude Ditch, Flint/Copper &
   Bog-Iron tools, Pine Palisade, Hand-Carts.
2. **Tier II — Timber & Bloomery:** Sawpit, Charcoal Clamp, Clay Bloomery,
   Granary, Timber Watchtower, Gambesons, Iron Spears & Roundshields, Charter
   Signal Beacon.
3. **Tier III — Stone & Ashlar:** Masonry Yard, Blast Hearth, Stone Curtain
   Wall, Gatehouse Murder-Holes, Keep Vault (raid-proof storage), Brigandine &
   Crossbows, Infirmary.
4. **Tier IV — Ironwood & Crucible Steel:** Treated Ironwood Beams (rot- and
   fire-resistant), Crucible Steel Forging, Ballistae/Wall Engines, Deep-Delve
   Lanterns, Great Keep Charter Hall.

Every tier deliberately cross-links skills (Factorio/ATITD style): smelting iron
needs **Mining** (bog ore) + **Woodcutting/Carpentry** (charcoal clamp) +
**Masonry** (clay/stone bloomery). Neither a solo generalist nor a single-skill
bot thrives as fast as a coordinated Household where characters specialize.

______________________________________________________________________

## 5. The Hold & Pre-Planned Raid Defense

Your **Hold** sits on your Household's chartered plot. Expanding it is both your
economic engine and your shield.

### 5.1 Hold Construction & Maintenance

- Buildings occupy Hold plots/slots, cost stockpiled materials, and accumulate
  `BUILD` work-time from one or more characters.
- Structures have **Condition (0–100%)** that weathers slowly and takes direct
  damage during Raids; `REPAIR` restores condition.
- **Vault Capacity vs. Open Yard Stockpile:** Early stockpiles sit in the open
  yard. Upgrading **Keep Vaults** protects a guaranteed floor of your most
  precious ingots, tools, and rations even if raiders breach the outer gate.

### 5.2 Raids: Asynchronous, Pre-Planned, Non-Lethal

As your Hold's wealth, noise (forges/mines), and season day-count climb,
regional **Raid Threat** rises (outlaws, desperate wildlings, beast packs, rival
empire scouts).

- Because players and bots aren't glued to the screen 24/7, **Hold Defense is
  pre-planned via a Defense Doctrine**:
  - **Role Assignments:** Designate how characters currently at the Hold respond
    when horns sound (e.g., *Maren → Wall Archer*, *Aldric → Gate Shieldwall*,
    *Non-combatants → Barricade inside Vault*).
  - **Tactical Triggers:** Set thresholds (e.g., *Loose pitch-arrows if raider
    strength > 20*, *Abandon Outer Yard and seal Inner Keep if Gatehouse drops
    below 25% HP*).
  - **Traps & Preparedness:** Pre-stock arrow bundles, boiling pitch, and
    mantlets.
- **Strict Raid Stakes Rule:**
  - **Zero Defender Permadeath:** Characters inside the Hold *never die* in a
    Raid. Worst case, defenders suffer temporary `Wounded` / `Concussed` debuffs
    (−25% work speed until rested/treated), outer buildings lose condition, and
    raiders carry off a percentage of **unvaulted** stockpile goods before
    withdrawing.
  - Every raid produces a rich, turn-by-turn **After-Action Chronicle** in the
    event log so you (or your bot) can diagnose *why* the eastern palisade
    buckled and adjust your blueprint/doctrine.

______________________________________________________________________

## 6. Field PvM & Party Expeditions

Outside the Hold, the wilds grow progressively more lethal with distance from
the Charter Coast.

- **Forming a Party:** You can group 2–5 characters from your Household into an
  **Expedition Party** at the Hold, equip them with weapons, armor, torches, and
  rations, and assign a **Combat Stance / Retreat Rule** before marching:
  - Frontline / Backline positioning (Shield-bearers soak hits; Ranged/Spears
    strike from the second rank).
  - **Retreat Trigger** (e.g., *Sound retreat if any member drops below 30% HP*
    or *Fight to the death*).
- **Encounter Resolution:**
  - Lairs (Wolf Dens, Brigand Stockades, Sunken Barrows, Ironwood Groves)
    resolve in deterministic combat rounds (each round taking a few seconds of
    action time, logged in crisp low-fantasy prose).
  - **Real Permadeath Risk:** If a character hits 0 HP in the field (or fails a
    retreat check while cut off), **they die permanently**. Surviving party
    members can attempt to haul back fallen comrades' gear if pack weight
    allows.
- **Why Risk PvM?**
  - Monsters guard high-purity mineral veins, ancient ironwood stands, intact
    pre-Charter masonry/steel salvage, and high-Favor war trophies for the
    Homeland.

______________________________________________________________________

## 7. Homeland Tribute & Recruitment Economy

At your Hold's **Charter Beacon / Supply Quay**, characters can load goods onto
outbound supply wagons (`SHIP_TRIBUTE`):

1. **Base Tribute Values:** Raw pine is worth pennies of Favor; ironwood planks,
   steel spearheads, Field Rations, and Barrow Relics are worth fortunes.
2. **Dynamic Homeland War Demands:** The Homeland periodically posts **War
   Dispatches** (e.g., *"Winter Siege at Highwatch: Cookware, Preserved Meat &
   Gambesons pay 2.0× Favor; Raw Stone pays 0.5×"*).
3. **Recruiting Settlers:** Spend accumulated **Charter Favor** (gated by
   available **Hold Beds**) to summon a fresh settler on the next supply ship
   (`RECRUIT_SETTLER`).
   - Replacing a lost starter up to 3–4 people is cheap (preventing soft-locks
     after a bad wipe in the woods).
   - Expanding from 8 → 9 → 10+ retainers scales steeply in Favor and food/bed
     upkeep, rewarding advanced Hold industrialization.

______________________________________________________________________

## 8. Emergence & Hypothesis-Testing Playground

Because you want Ironwood Wilds to double as a **living laboratory** where bots
(and eventually `samgenai` genetic programs) inhabit a tweakable land:

| World Knob (`WorldHypothesisConfig`)    | Example Hypothesis You Can Test & Film                                                                                                     |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| **Resource Regeneration vs. Depletion** | *If surface bog-iron never respawns, how quickly do bot households transition from peaceful gatherers into deep-wild PvM delving parties?* |
| **Skill XP Curve Steepness**            | *Does steeper OSRS XP scaling cause evolved households to invent strict caste division (1 pure smith + 3 haulers) vs. jack-of-all-trades?* |
| **Raid Frequency & Scaling**            | *Under heavy raid pressure, do populations turtle with tiny high-wall Holds, or over-expand rosters to man towers?*                        |
| **Homeland Price Shocks**               | *If the Homeland suddenly triples Favor for Steel Weapons mid-season, how fast do bot queues reallocate labor from woodcutting to mining?* |
| **Retreat Thresholds & Mortality**      | *What party retreat HP% maximizes long-run Household Tribute when accounting for veteran skill loss on permadeath?*                        |

Every world run logs structured economic, skill-distribution, mortality, and
Tribute metrics per game-hour — giving you an instant dataset for charts,
hypothesis write-ups, and GA fitness scoring.
