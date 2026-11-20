# Ironwood Wilds — Gameplay and World Plan

## 1. High concept

Your household has been sent through a frontier gate to **Ironwood Wilds**, a resource-rich, quasi-low-fantasy world. Home is under attack by another power exploiting similar frontiers, and everything useful you send back helps it endure. Your people begin with a rough camp, a few skills, and more instructions than answers. They can become miners, foresters, smiths, builders, scouts, and fighters; establish a fortified holding; uncover regional technologies; ship valuables home; and replace those who do not return from expeditions.

There is no scripted central plot and no PvP. The game is a machine for producing legible stories: a delayed shipment caused by a flooded road, a desperate wolf hunt to replace a broken weapon, a prosperous mine whose depleted seam forces a risky relocation, or a holding that survives a raid because its owner planned the defence weeks earlier.

### Design pillars

1. **Direct a household, not one avatar.** Several characters act independently. The player is a coordinator and planner.
2. **Patience, not ambient silliness.** An idle character waits. No autonomous eating, wandering, arguing, or random task selection unless a later, explicit rule says otherwise.
3. **Time is the main cost.** Gathering, travel, research, construction, and recovery take time. Planning a good queue matters more than clicking quickly.
4. **Geography should matter.** Where resources, hazards, and routes occur must change what a sensible household does.
5. **Real consequences, bounded cruelty.** Expeditions can kill characters; holdings can lose stock and progress without arbitrarily deleting a whole household offline.
6. **Watchable causality.** The event chronicle explains what happened in terms of preceding actions and visible world state.
7. **Bots are citizens, not cheats.** Anything a bot can know or do is available through the public protocol to a human client.

## 2. The frame narrative and world seasons

The home-world war is a frame, not a story railroad. It explains why shipments are valuable, why newcomers arrive, and why worlds can be opened, closed, and archived. The enemy's exact nature, the gate mechanism, and the metaphysics remain open so that later content does not contradict early lore.

A **world** (also called a season in UI copy) has:

- a unique world ID and generation seed;
- a pinned ruleset and generator version;
- a beginning and an optional announced end;
- its own resources, households, characters, technology, and Home Favor;
- a final archive/chronicle when closed.

Accounts survive world closure. World-specific power does not automatically carry into a fresh world. Later, cosmetic names, banners, chronicles, and perhaps side-grade starting doctrines can provide continuity without making every new season unfair.

- **Operational restart:** resumes exactly the same world.
- **Season reset:** creates a new world and new household state under existing accounts.

## 3. The entities a player cares about

| Entity | Meaning |
|---|---|
| Account | A login identity; may control one household per world initially |
| Household | The player's presence in one world: banner, Home Favor, unlocked knowledge, holding, roster |
| Character | An individually commanded person with location, skills, health, equipment, and an action queue |
| Holding | The household's camp/house/castle and its stockpiles, workshops, defences, and shipment point |
| Site | A named resource, landmark, monster lair, or other interactable place on the map |
| Expedition | A temporary group of household characters undertaking travel and/or combat together |
| World power | The non-player threat used for PvM, raids, and low-fantasy flavour; not another player faction |

One account/one household/one active holding is enough for the first version. Cooperative households and multiple bases are possible later but should not distort early ownership and permission rules.

## 4. The basic loop

1. **Observe** the household, nearby geography, queues, stock, known sites, and upcoming threats.
2. **Choose** a goal: better tools, a new room, a technology, a shipment, recruitment, or a hunt.
3. **Queue** movement and work for individual characters.
4. **Reorganise** attention while those actions run; other characters can work in parallel.
5. **Resolve** completions, failures, discoveries, injuries, and resource changes.
6. **Invest** in capability: skill XP accrues to people, technology belongs to the household, buildings improve capacity, and equipment changes action options.
7. **Ship or risk.** Send surplus home for Favor, or form an expedition for rarer rewards and potential casualties.
8. **Adapt** to depletion, weather, monster pressure, casualties, and new geography.

### Illustrative fifteen-minute household plan

Numbers below are examples, not balance commitments.

- **Alda:** travel to the iron ridge (2 min), mine ore (3 min), mine ore again (3 min).
- **Bran:** gather wood (2 min), craft a pegged crate (2 min), prepare a shipment (1 min).
- **Cerrin:** research Toolmaking at the camp (6 min).
- The player opens each character in a separate browser tab, adjusts Bran's queue when the storehouse fills, and forms a two-person expedition once Alda returns.
- When the shipment completes, the household gains Favor. It can save toward another character or spend on a replacement after a failed hunt.

The desired experience is cycling through a small roster to make consequential plans, not keeping every person constantly busy at any cost.

## 5. Characters and action queues

Each character has:

- stable ID, name, household, status, and current location;
- health and possible injuries;
- individual skill XP/levels;
- equipment and a small carried inventory if encumbrance is enabled;
- one running action and a short pending queue;
- an explicit standing defence role, if assigned;
- a lifetime chronicle of important events.

A character with no queued work is **Idle**. Idle is a legitimate state, not an error and not an invitation for autonomous behaviour.

### Queue rules worth keeping

- One running action plus roughly 3–5 pending actions; the exact cap is a rules value.
- Appending, replacing, reordering, and clearing pending work are commands.
- Enqueue validation catches impossible shapes, ownership errors, unknown targets, and queue overflow immediately.
- Start validation checks the live world: resources, target occupancy, technology, health, location, and capacity can have changed since enqueue.
- A start-time failure creates an `action_failed` event and advances to the next queued action. It does not silently retry forever.
- Inputs are consumed at action start; outputs are created at completion. Cancellation and death rules must state whether already-consumed inputs are lost.
- Running-action cancellation may be disallowed for some actions. Pending actions can always be cancelled.

The UI shows both a predicted schedule and the authoritative `not_before`/completion values. Predictions are estimates until an action starts because earlier work may fail or world conditions may change.

## 6. Geography, travel, and discovery

The world is spatial but presented as text and compact maps rather than a graphical walking simulator.

- A bounded world contains regions; regions contain a tile grid and named sites.
- Travel follows valid routes between adjacent tiles/locations, with terrain, weather, roads, load, and injuries affecting duration.
- Sites include groves, seams, ruins, dens, crossings, springs, and defensible clearings.
- Households know only discovered geography. Public landmarks can be shared, but another household's stock, roster, and plans are private.
- A movement command names a destination, not an animation. The server calculates the route and duration. Initial pathfinding can be deterministic A* over known passable terrain.
- Early actions may require the character to be at a specific site. Later, expedition commands can fold multi-leg travel into one plan without removing the underlying geography.

A resource node is not just an infinite command target. It has amount, accessibility, regeneration/depletion rules, and connections to its local ecology. The first slice may use simple depletion and scheduled regrowth; deeper ecology comes later.

## 7. Skills, technology, and progression

These are three separate progression axes and should not be conflated.

### 7.1 Character skills

Skills rise through completed work and occasionally through training. Examples:

- Woodcutting
- Mining
- Foraging
- Crafting
- Smithing
- Building
- Research
- Survival
- Combat

A skill can affect duration, yield, quality, injury resistance, or available actions. Use transparent integer/fixed-point formulas and diminishing or stepped benefits so clients can display exact calculations.

**Failure should not always grant full XP.** Grant a small learning amount where sensible, but avoid making deliberate failure the optimal grind.

### 7.2 Household technology

Technology is knowledge shared by the current household in the current world. Research is performed by one or more characters at an appropriate holding structure and consumes time plus inputs.

Illustrative branches:

| Branch | Early | Middle | Later |
|---|---|---|---|
| Material | Knapped tools | Bloomery iron | Crucible steel |
| Woodland | Managed coppicing | Seasoned timber | Ironwood cultivation |
| Construction | Raised storehouse | Palisade and ditch | Stone keep |
| Expedition | Field dressing | Formation tactics | Wardcraft |
| Logistics | Pack frames | Cart roads | Gate caravans |
| Stewardship | Soil testing | Seed selection | Managed ecosystems |

Nodes have prerequisites, visible costs, and explicit unlocks. Avoid a giant web at launch: three short branches with real choices are better than a hundred nodes containing `+1%`.

### 7.3 Home Favor and recruitment

Shipping designated resources or artefacts home grants **Home Favor**. Favor is household/world currency, not character XP and not a universal cash substitute.

Primary sink: a new character charter. Other possible sinks include emergency supplies, a replacement expedition kit, or a one-off survey. Recruitment costs can rise with active roster size, and a rules-level roster cap prevents unlimited tab management.

A household with no living characters gets a clearly defined recovery path so PvM cannot permanently brick an account. In the vertical slice, zero living characters plus insufficient Favor for a normal recruit enables one delayed, free emergency newcomer through the ordinary recruitment command.

## 8. Resources, crafting, and holdings

### Resources and items

Use immutable item definitions and mutable stacks/instances. Definitions specify stable key, category, stackability, mass, shipment value, and tags; only items that need durability or individual quality require unique instances.

Start with a short, coherent chain rather than unrelated resources:

```text
wood + stone -> basic tools -> safer/faster gathering
iron ore + fuel + technology -> iron fittings -> structures and weapons
wood + fittings -> storehouse / palisade / shipment crates
selected surplus + crate -> shipment -> Home Favor -> new character
```

### Buildings

A holding has plots or abstract rooms rather than requiring pixel-perfect placement. Construction consumes materials and accumulated character work. Buildings can provide:

- storage capacity and raid protection;
- research/crafting stations and worker slots;
- beds/roster capacity;
- roads and travel reductions;
- defensive positions;
- shipment throughput.

A house-to-castle progression is a major long-term goal, but the first playable version needs only a camp, storehouse, workshop, and one defensive structure.

### Upkeep and storage pressure

Storage limits and upkeep should create decisions, not punitive offline chores. Any decay must be slow, telegraphed, and capped. Do not copy hunger/building-decay systems merely because `samvillage` has them; add them only if they improve this loop.

## 9. PvM expeditions

Expeditions provide focused danger away from the holding.

1. The player selects two or more eligible characters and a discovered target.
2. The player assigns formation slots, equipment, basic tactics, and a retreat threshold.
3. The server validates the group and reserves its members so they cannot simultaneously perform solo work.
4. Travel and combat rounds are scheduled domain events.
5. Characters use the preselected tactics; no twitch input is required.
6. The expedition returns with loot, injuries, skill gains, and possibly deaths.

Example standing tactics are deliberately small: `hold_front`, `focus_marked`, `guard_wounded`, `retreat_at_40_percent`. They are explicit plans, not random idle AI.

Combat should expose a forecast with important assumptions and produce a round-by-round chronicle. Randomness must be seeded and replayable. Low fantasy means injuries, equipment, terrain, numbers, and preparation matter more than flashy spell rotations.

### Death

- Expedition combat can permanently kill characters.
- Death cancels their queues, resolves carried items according to explicit loot/recovery rules, and releases reservations.
- Dead IDs remain in history and are never reused.
- There is no PvP kill, theft, or deliberate player-triggered raid path.

## 10. Holding defence and raids

Raids are PvE attacks on the holding, distinct from expeditions.

- The threat is telegraphed with a scheduled window and enough information to prepare.
- The player preassigns defenders, positions, equipment use, fallback order, and supplies to protect.
- The defence executes whether or not the player is online.
- **Raid defeat cannot permanently kill household characters.** It can wound them, consume supplies, damage structures, and steal unprotected stock. Later rules may interrupt work; the first slice deliberately leaves unrelated running/pending actions untouched and only uses listed idle/`defend` characters at the holding.
- Raid outcomes scale from telegraphed threat and actual defence, not from whether a player happened to be logged in.
- A new or devastated household receives a protection/grace rule to prevent repeated unavoidable losses.

This makes castle construction meaningful without turning absence into account deletion.

## 11. Emergence without random character behaviour

The desired emergence comes from systems intersecting with commanded goals:

- a forest regrows more slowly after every nearby grove is cut;
- rain makes a crossing slower but replenishes soil and water;
- hunting wolves changes prey and vegetation pressure;
- a rich seam encourages a road and storehouse, which attracts a raid;
- a casualty delays a shipment, which delays recruitment, which changes the next expedition;
- several households exploit different parts of the same region and observe shared environmental changes.

Characters remain patient. Monsters and environmental systems may have their own scheduled behaviours. Bots create purposeful social/economic dynamics by choosing commands, not by receiving privileged engine-side goals.

## 12. Grind with respect for the player

OSRS-style longevity is compatible with respectful scheduling:

- long actions replace repeated clicking;
- queues are short enough to require prioritisation but not constant attention;
- skill gains and requirements are inspectable;
- different activities create different constraints rather than identical progress bars;
- no PvP means no need to design around player harassment;
- production values should be testable at accelerated logical speed;
- monetisation, if ever added, must not sell queue skips or casualty recovery.

The critical playtest question is not “is there enough grind?” but **“does returning to a character reveal an interesting decision?”**

## 13. First-version boundaries and later questions

### Required for the first slice

Several characters, map locations, timed queues, gathering, crafting, technology, construction, shipping, Favor recruitment, one group PvM encounter, one holding raid, persistence, browser/CLI/bot access.

### Good later additions

Trade and cooperation, richer needs, item quality, multi-holding logistics, weather fronts, detailed ecology, guild-like compacts, more sophisticated defence plans, seasonal world modifiers.

### Decisions to validate through play rather than settle now

- Whether food/rest is a light action prerequisite or a full needs system.
- Whether households can trade directly and which shared-world resources matter.
- Whether world time advances during extended host downtime; the initial recommendation is no.
- Exact roster cap, queue cap, XP curves, action durations, and Favor economy.
- Whether any account-level progression exists beyond archives and cosmetics.
