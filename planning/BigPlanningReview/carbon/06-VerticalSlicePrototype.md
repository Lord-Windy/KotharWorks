# 06 — The Vertical Slice Prototype ("The Timber & Bog-Iron Charter")

You wrote:

> *"I also want the prototype of what this looks like, a slice of some sort and
> how it would work."*

Remember Mr. Stansfield's advice and your Friday-only schedule: **the #1 threat
to a Friday project is a 6-week horizontal plumbing trench where nothing moves
on screen.** If every Friday ends with a runnable vertical slice that bots can
play and you can watch, momentum compounds itself.

Here is **Slice Zero: "The Timber & Bog-Iron Charter"** — small enough to build
cleanly across ~5–6 Fridays, yet touching **every single architectural pillar**
(procgen geography + prose, multi-tab characters, lazy/heap queues, skills, Hold
construction, pre-planned Raid defense, Tribute recruitment, Party PvM
permadeath, Postgres schemas, and bots).

______________________________________________________________________

## 1. Exact Scope of Slice Zero

### What Is IN (Proving Every Seam)

| Pillar                        | Slice Zero Concrete Scope                                                                                                                                                                                 |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **World & ProcGen**           | Seeded 5×5 frontier region patch per Charter Ring; 4 site archetypes (`Pine Barrens`, `Bog-Iron Fen`, `Slate Rise`, `Wolf-Barrow Den`) + state-bound `look`/`examine` prose via `libs/samprocgen`         |
| **Household & Tabs**          | 1 Household per Account; starts with **3 patient retainers**; multi-tab `samhtml` UI (`[🏰 Hold]` + 1 tab per living character + `[+ Recruit]`)                                                           |
| **Skills (4)**                | `Woodcutting`, `Mining`, `Smithing` (covers smelting + crafting in Slice 0), `Vanguard` (combat) — full OSRS XP table                                                                                     |
| **Goods (7)**                 | `Pine Log`, `Slate Stone`, `Bog-Iron Ore`, `Charcoal`, `Iron Ingot`, `Iron Spear`, `Field Rations`                                                                                                        |
| **Action Queue**              | Lazy timestamps + Causal Event Min-Heap; `1 active + 4 pending` with batch `count` (`TRAVEL`, `CHOP`, `QUARRY`, `MINE`, `SMELT`, `FORGE_SPEAR`, `BUILD`, `REST`, `UNLOAD`)                                |
| **Tech & Hold (3 Buildings)** | **2 Tech nodes** (`Bloomery Smelting`, `Timber Fortification`); **3 Hold structures** (`Timber Palisade`, `Clay Bloomery`, `Keep Vault`)                                                                  |
| **Hold Raid**                 | Periodic **Outlaw Raid** every ~2 game days; pre-planned **Defense Doctrine** (`Man Palisade` vs `Hide in Vault`); damages Palisade & steals unvaulted logs/ore on breach; **zero defender deaths**       |
| **Party PvM**                 | Form a 2–3 character party (`Retreat if any HP < 30%` toggle) to assault the **Wolf-Barrow Den**; yields high-Favor `Ancient Bronze Torc` + unlocks rich inner iron seam; **0 HP = permadeath + epitaph** |
| **Tribute & Recruit**         | `SHIP_TRIBUTE` converts Ingots/Spears/Torcs → **Charter Favor**; `RECRUIT_SETTLER` spends Favor to add a 4th/5th retainer (or replace a fallen one)                                                       |
| **Bots & Persistence**        | `auth` + `wilds` + `wilds_meta` Postgres schemas; `samhttp` JSON API; `apps/samwilds-bot` running **20 concurrent bot households** on the same server!                                                    |

### What Is Mercilessly CUT from Slice Zero (Saved for Slice 1+)

- WebSockets & raw TCP socket (Slice 0 uses `samhttp` REST + local JS countdowns
  \+ 2s cursor sync — zero protocol yak-shaving!).
- Multi-room dungeon branching (Wolf-Barrow is a 2-wave encounter in Slice 0).
- Equipment beyond Main-Hand (`Iron Spear` / starter tools).
- Genetic algorithms (`samgenai` hooks in *after* `samwilds-bot` proves the
  economy balances).

______________________________________________________________________

## 2. Walkthrough: How 15 Minutes of Slice Zero Actually Plays

### Minute 0: Charter Landing

1. You open `http://127.0.0.1:8140`, register `LordWindy`, and found **House
   Ashvale**.
2. Server allocates a starter plot at Region `(2, 0)` on the Charter Ring
   (`world_seed = 0x57494C44`), spawns **Ashvale Hold** (`site-1`) with 10 Field
   Rations, and generates three retainers:
   - **Aldric** (Traits: *Broad-Shouldered*, Woodcutting 3)
   - **Brenn** (Traits: *Bog-Born*, Mining 3)
   - **Maren** (Traits: *Keen-Eyed*, Vanguard 3)
3. All three character tabs glow `[● IDLE]`. Clicking **Aldric's** tab renders
   `samprocgen`'s `look` output:
   > *"A raw clearing hacked from coastal pine shingle. A cold salt mist drifts
   > off the Reach. Three canvas lean-tos huddle beside an unlit hearth-pit."*

### Minutes 1–6: Splitting Labor & Lazy Queue Chaining

- **Tab 1 (Aldric):** You click `[Queue Preset: Gather Pine]` → posts
  `[TRAVEL → Pine Barrens (30s), CHOP_PINE × 5 (5 × 25s), TRAVEL → Hold (30s), UNLOAD (5s)]`.
  Total = `3m 10s`. Aldric's tab badge updates immediately to
  `[🌲 Aldric · 03:10]` and ticks down smoothly in JS. Zero server work occurs
  for the next 30 seconds!
- **Tab 2 (Brenn):** Queues
  `[TRAVEL → Slate Rise, QUARRY_SLATE × 4, TRAVEL → Hold, UNLOAD]`. Tab badge:
  `[⛏ Brenn · 03:45]`.
- **Tab 3 (Maren):** Queues `[SURVEY_REGION (60s)]` — at `01:00`, the heap pops
  Maren's survey, rolling `coord_seed` for Region `(2, 0)`'s hidden POI and
  revealing **Mist-Choked Bog Fen (`site-4`)** and **The Wolf-Barrow
  (`site-5`)** in the Chronicle!

### Minutes 7–11: Tech Unlock, Bloomery & Pre-Planned Raid Doctrine

- Logs and Slate hit the Hold stockpile. On `[🏰 Hold]`, you unlock **Bloomery
  Smelting** and start constructing the **Clay Bloomery** (`BUILD` queued on
  Aldric & Maren in parallel — construction work accumulates cleanly across
  multiple builders!).
- Brenn brings back Bog-Iron Ore; Aldric burns Charcoal and smelts 4
  `Iron Ingots`, hitting **Smithing Lv 5**, then forges two `Iron Spears`.
- **Raid Warning Horn:** The Chronicle warns *"Outlaw scouts spotted on the
  ridge — Raid expected within 4 minutes."*
- On `[🏰 Hold → Defense Doctrine]`, you toggle:
  - *Vault Lock:* `[✓] Iron Ingots`
  - *Defenders:* `Maren (Iron Spear) → Palisade`, `Aldric → Palisade`,
    `Brenn → Shelter`
- At `11:30`, `EVENT_HOLD_RAID_DUE` pops on the simulation heap:
  - Outlaws (Power 14) clash against Palisade + Maren + Aldric (Power 18).
  - Palisade drops from `100% → 71%` condition; Maren takes a minor bruise
    (`-12 HP`, alive!); **0 items stolen**; Maren & Aldric earn
    `+35 Vanguard XP`, and an 8-line battle report lands in your Chronicle while
    Brenn never even stopped mining!

### Minutes 12–15: Party PvM Stakes & Homeland Tribute

- You form a 2-person Party (**Maren + Aldric**, both wielding Iron Spears,
  `Retreat < 30% HP`) and dispatch them to **The Wolf-Barrow (`site-5`)**.
- Over 4 combat rounds (10s each), they slay the Barrow-Hound Alpha, suffer
  heavy hits (Aldric drops to `24/100 HP`, triggering clean withdrawal after
  Wave 1 loot, or pushes through Wave 2 to claim the `Ancient Bronze Torc`!).
- Back at the Hold, Maren loads 2 Iron Spears + the Bronze Torc onto the
  **Charter Wagon (`SHIP_TRIBUTE`)** → `+120 Charter Favor`.
- You click `[+ Recruit (100 ★)]` → **Jory** arrives on the tab bar as your
  **4th controllable tab (`[● Jory · IDLE]`)**, ready to take over full-time
  charcoal burning so Aldric can specialize!

______________________________________________________________________

## 3. Suggested Friday-by-Friday Build Plan

Designed so every single Friday closes with `ctest` green and something visibly
cooler to run or record:

| Session      | Deliverable                                                                                                                         | End-of-Friday Demo                                                                                                                                                                           |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Friday 1** | **`libs/samprocgen` MVP** (`samrena` + `samdata` + `samstring`)                                                                     | CLI binary prints seeded frontier regions, biomes, and `look`/`examine` descriptions as you tweak `yield_pct` and `condition_pct`                                                            |
| **Friday 2** | **`libs/samwilds` Core Skeleton** (Pools, Generational IDs, Min-Heap Lazy Scheduler, Gather/Travel/Unload, Determinism Golden Hash) | Headless C test simulates a 3-person Household running queued mining/chopping chains across 24 simulated hours in **< 5 milliseconds**                                                       |
| **Friday 3** | **Skills, Tech, Hold Construction, Raid Resolver & PvM Party Combat** inside `libs/samwilds`                                        | CLI replay prints a full Household week: Bloomery built, Outlaw Raid repelled via pre-planned doctrine, Wolf-Barrow cleared (plus a deliberate permadeath test case!)                        |
| **Friday 4** | **PostgreSQL Adapters (`auth`, `wilds`, `wilds_meta`) + `samhttp` JSON API** in `apps/samwilds-server`                              | Live server boots on `:8140`; you play a Household via `curl` / `samwilds-cli`, `kill -TERM` the server mid-mine, restart it, and watch timers resume seamlessly                             |
| **Friday 5** | **Multi-Tab Browser UI (`samhtml` + inline JS timers)**                                                                             | Open browser, register, click across `[Hold]` and 4 character tabs with live ticking countdowns, `look`/`examine` prose, and Raid Doctrine controls                                          |
| **Friday 6** | **`apps/samwilds-bot` + Spectator Telemetry Page**                                                                                  | Boot **30 autonomous bot households** into one valley, tweak `WorldHypothesisConfig` (double iron scarcity or raid pressure), and watch emergent specialization unfold live on `/chronicle`! |

*(And Friday 7+? Wire `libs/samgenai` into `samwilds-bot` and film Generation 1
vs. Generation 200 households competing for Homeland Favor!)*
