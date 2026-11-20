# Ironwood Wilds — Vertical Slice: “The First Charter”

## 1. Purpose

The slice should prove the whole shape of the project at very small scale, not merely demonstrate pathfinding or a webpage. It is successful when a person can direct a tiny household through a coherent economic and combat loop, an external bot can do the same through the API, and a process restart preserves the story without duplicating anything.

### Hypotheses being tested

1. Cycling among several patient characters and short queues creates interesting decisions.
2. Geography, timed work, skills, technology, and storage interact enough to produce different plans.
3. Shipping, recruitment, PvM casualties, and holding defence form a comprehensible loop.
4. Humans and bots can genuinely use the same protocol.
5. Lazy scheduling, deterministic records, and persistence are manageable in the Ptah C11 stack.

If (1) is boring, more resources and a larger tech tree will not fix it. The slice must expose that early.

## 2. Exact scope

### World and accounts

- One bounded world with one starting region materialised at world creation.
- Up to four test households; two are enough for ownership tests.
- Account registration/login, browser sessions, and scoped CLI/bot tokens.
- One household per account and one starting holding.
- Three starting characters per household.
- No co-op household membership yet, although the schema does not preclude it.

### Geography

The generator always supplies or repairs a start to contain:

- the household camp;
- a wood source (`old_coppice`);
- a stone/iron site (`iron_ridge`);
- a water feature that affects at least one route;
- one low-tier monster site (`grey_wolf_den`);
- reachable terrain and a small number of alternate routes/sites.

The map is a compact ASCII/text grid plus structured site lists. There is no free-moving graphical avatar.

### Characters

Fields: ID, name, household, location, health, status, four skills, one running action, three pending actions.

Skills:

- Woodcutting
- Mining
- Crafting
- Combat

Research uses a fixed duration/cost in the slice rather than requiring a fifth skill system. There are no hunger, sleep, mood, aging, relationships, pregnancy, or random idle activities.

### Resources and items

A deliberately small set:

| Definition | Purpose |
|---|---|
| `wood` | Construction, fuel, crates, shipment |
| `stone` | Basic toolmaking and construction |
| `iron_ore` | Ironworking and shipment |
| `iron_fitting` | Advanced construction/equipment ingredient |
| `hand_axe` | Simple equipment demonstrating a gathering modifier |
| `shipment_crate` | Required shipment container |
| `wolf_pelt` | Expedition loot with a small shipment value |

Only equipment that needs state becomes an item instance. Ordinary resources are stacks.

### Technology

Three nodes are enough to prove the mechanism:

```text
Toolmaking I
  └── Ironworking I

Fortification I
```

- **Toolmaking I:** unlocks `hand_axe`.
- **Ironworking I:** unlocks smelting/crafting `iron_fitting` from ore plus fuel.
- **Fortification I:** unlocks the palisade.

The tech data pack demonstrates prerequisites, research time/cost, household ownership, and client-readable rules. It is not yet a large tree.

### Buildings

- **Camp:** prebuilt; basic storage, one crafting/research slot, shipment point.
- **Storehouse:** increases storage and protects a configurable portion of stock in raids.
- **Palisade:** requires Fortification I and iron fittings; reduces raid losses.

A separate Workshop is deferred; the camp supplies the slice's one crafting/research slot.

Construction creates a site, then a character contributes the listed timed `build` work. `holding.building.place` validates technology/plot and atomically attaches the full material cost to the site; `build` consumes no additional stock. Construction-site cancellation is not offered in the slice, so there is no partial-refund rule. There is no freeform placement beyond choosing an available holding plot.

### Actions and commands

- `move`
- `gather_wood`
- `quarry_stone` (can share a generic gather handler)
- `mine_iron`
- `craft`
- `research`
- `build` and `repair`
- `deposit` at the holding
- `rest` at the holding for timed injury recovery
- `defend` at the holding for a scheduled raid
- queue append/replace/clear/cancel
- `character.equipment.set` to assign/unassign an available equipment instance
- place building
- dispatch shipment
- recruit character
- create/update/dispatch/cancel expedition
- set holding defence plan

Every action uses integer logical durations. Production-like defaults may be minutes; tests use explicit virtual time.

The slice distinguishes **household stock at the holding** from a character's **20-unit carried inventory**. Gathering/mining produces carried items; returning to camp does not automatically deposit them. A short `deposit` action at the holding transfers selected carried stacks into household stock. Research, camp crafting, construction, and shipments use household stock; equipment instances are assigned from household stock by `character.equipment.set` only while that character is at the holding. A dead character's carried items remain with the encounter and are recoverable only if the expedition wins/returns to that site under the encounter's loot rule.

### Shipment and recruitment

Baseline slice rule: a shipment consumes `1 shipment_crate + 10 wood + 5 iron_ore`, takes 120 seconds, and grants 10 Home Favor on arrival; each included `wolf_pelt` (maximum two) adds 2 Favor. A normal recruitment costs 10 Favor, schedules a newcomer at camp in 120 seconds, and is limited to six living **plus incoming** characters; the roster slot is reserved when Favor is spent. Costs and arrival are idempotent effects.

If the household has **zero living characters, no incoming charter, and fewer than 10 Favor**, the same `household.recruit` command becomes an emergency charter: it reserves the sole emergency slot and creates exactly one unequipped basic character for 0 Favor after 300 seconds. It can be used again only if the household meets all those conditions again, and never supplements a living/incoming roster. This makes zero-character/zero-Favor recovery possible without a special admin command.

### PvM

- One expedition destination: `grey_wolf_den`.
- Party size **2–3**, matching the game's group-expedition rule.
- Formation: front/back; tactics: `focus_target` and `retreat_at_threshold` (default 40 health).
- Scheduled travel, five-second deterministic combat rounds, injuries, possible death, `wolf_pelt` loot, and return.
- `rest` is required, not optional: a resting character at camp recovers 25 health after 120 seconds, up to 100.
- A test fixture must exercise both a successful hunt and a fatal hunt even if the curated demo seed usually succeeds.

### Holding raid

- One telegraphed `raider_band` encounter type. The first raid is scheduled and visible at household creation for logical time `created + 30 min`; later raids are 60 minutes apart. This is the new-holding grace period, and tests use virtual time rather than waiting.
- Standing plan: selected defenders, front/back order, protected stockpile priority, retreat threshold. Defenders must be living, at their own holding, not on an expedition, and either idle or running `defend` when the raid starts. An idle listed defender follows the standing plan; a character running other work does **not** participate.
- Raid start does not cancel ordinary running/pending work or refund inputs, because it does not interrupt that work in the slice. Players deliberately queue `defend` or leave a listed defender idle. Normal stale-completion checks still apply to death, cancellation, and entity replacement.
- Palisade/storehouse/defenders affect the result. Defeat can remove unreserved stock and damage the palisade; it cannot steal input/output reservations or reduce a character below 1 health.
- Defeat cannot permanently kill characters; health loss is recovered through explicit `rest`. Palisade damage can be restored with `repair`.

### Baseline v1 rules to pin during M0

These values are the proposed initial content pack, not a claim of good final balance. M0 either accepts or edits this table and then treats the accepted version as normative; implementation and tests must not scatter different numbers through code.

**Starting state:** Alda has Mining 2, Bran Woodcutting 2, Cerrin Crafting 2, and all other skills 1; everyone has 100 health. Camp stock starts with `4 wood + 2 stone`, 40 total stack-unit capacity, and 0 Favor. The coppice contains 30 wood and regrows one unit on a 60-second scheduled event up to 30; the ridge contains 30 ore and 20 stone, which do not regrow in the slice.

| Action/recipe | Time | Cost and effect |
|---|---:|---|
| Move | 30 s × integer path movement points | Terrain movement point cost is 1–4 per tile; deterministic route |
| Gather wood | 60 s | +3 carried wood; Woodcutting XP |
| Quarry stone | 90 s | +2 carried stone; Mining XP |
| Mine iron | 120 s | +2 carried ore; Mining XP |
| Deposit | 15 s | Transfer selected carried stacks to camp stock |
| Rest | 120 s | Restore 25 health, maximum 100 |
| Craft `hand_axe` | 60 s | 2 wood + 2 stone; requires Toolmaking I; Crafting XP |
| Craft `shipment_crate` | 60 s | 3 wood; Crafting XP |
| Craft `iron_fitting` | 120 s | 2 ore + 1 wood; requires Ironworking I; Crafting XP |
| Research Toolmaking I | 120 s | 2 wood + 2 stone; no prerequisite |
| Research Ironworking I | 180 s | 4 ore + 2 wood; requires Toolmaking I |
| Research Fortification I | 180 s | 4 wood + 4 stone; no prerequisite |
| Build Storehouse | 180 work-seconds | 6 wood + 4 stone; +40 stock capacity; protects the first 10 units selected by the defence plan |
| Build Palisade | 240 work-seconds | 10 wood + 2 iron fittings; requires Fortification I; 100 condition and +10 raid defence while condition > 0 |
| Repair Palisade | 60 s | 1 wood; restore 25 condition, maximum 100; Crafting XP |

Skill thresholds for levels 1/2/3/4/5 are 0/100/300/600/1000 XP. Ordinary successful work grants 20 XP in its associated skill. Skill-modified duration is `ceil(base_ms × (20 - (level - 1)) / 20)`, giving 100%/95%/90%/85%/80% at levels 1–5. The equipped hand axe then multiplies Gather Wood duration by 3/4, again rounding up, and adds 2 combat attack. Research, deposit, rest, and movement do not receive a skill modifier in the slice.

**Expedition baseline:** characters have 100 maximum health, 10 attack + 2 per Combat level above 1, and 2 defence. A grey wolf has 35 health, 12 attack, and 0 defence. Every five-second round uses stable initiative (characters 10, wolves 9, then entity ID). Damage is `max(1, attack + deterministic_roll(0..2) - defence)`. Characters focus the selected living wolf; each living wolf attacks the first living front-slot character, or the back slot if no front remains. A character whose retreat rule is enabled leaves at the start of its turn when health ≤ 40. Health 0 is death. Victory awards 10 Combat XP per participated round plus 20 for victory and one `wolf_pelt` per killed wolf. A fatal fixture uses two wolves, pre-injured characters, and retreat disabled rather than relying on a lucky demo seed.

**Raid baseline:** the raid lasts three five-second report rounds. Defence score is the sum of `5 + Combat level` for each eligible defender, plus 10 for an active palisade; the raider score is 20. At score ≥ 20, the holding wins and each participating defender loses 5 health (minimum 1). On defeat, the storehouse protects its first 10 units by standing priority, then the raiders take 10% (rounded up) of the remaining **unreserved** stock units, halved if a palisade is active; any active palisade loses 25 condition and each participating defender loses 10 health (minimum 1). The event payload itemises every step. No raid roll can kill, steal reservations, or interrupt an unrelated running action.

## 3. Curated demo walkthrough

### Act I — establish the household

1. Register an account and create a household in a new seeded world.
2. Open the household dashboard. The camp and three characters are visible; the map shows the coppice and a route toward the ridge.
3. Open **Alda**, **Bran**, and **Cerrin** in three browser tabs.
4. Queue Bran to move to the coppice, gather wood, return to camp, and `deposit`; make a later stone trip.
5. Queue Alda to move to the ridge, mine ore, return to camp, and `deposit`. The return journey alone does not change household stock.
6. Queue Cerrin to research Toolmaking I.
7. Watch event-cursor updates. No fixed global tick scans idle characters between deadlines.

### Act II — specialise and ship

8. Craft a hand axe, assign it with `character.equipment.set`, and observe its explicit gathering modifier on Bran.
9. Research Ironworking I; turn ore and wood fuel into an iron fitting.
10. Build a storehouse, craft a shipment crate, and dispatch the first bundle home.
11. While the shipment travels, another household account verifies that it cannot read private stock or command this household's people.
12. The shipment arrives; Favor is awarded and the household recruits a fourth character.

### Act III — risk and defence

13. Equip two characters, create an expedition, assign front/back and retreat rules, and attack the wolf den.
14. Read the deterministic round chronicle and loot report. In a separate fixture, demonstrate death and Favor-funded replacement.
15. Research Fortification I and build a palisade.
16. Set the holding defence plan before the announced raid window.
17. Resolve the raid while the controlling player is logged out; log in to a readable result showing defended stock, losses, injuries, and building effects.

### Act IV — prove the machinery

18. Restart the server while another character has a running mine/build action. Confirm the same world, queue, deadline policy, stock, and agenda resume without duplicate output.
19. Retry a committed shipment/recruit command with the same command ID; confirm the original response and exactly one effect.
20. Run the scripted bot on a fresh accelerated world until it completes the economic goal and the chosen combat/defence goal.
21. Replay the recorded command admissions and compare authoritative events and state hash.

## 4. Implementation slices inside the vertical slice

Each step should leave something runnable and tested.

| Step | Demonstrable result |
|---|---|
| A. Fixed-map core | Three characters move/gather/craft through in-process commands; queues and lazy agenda pass tests |
| B. Durable account/world skeleton | One account can log in, resume a snapshot/journal, and safely retry a command |
| C. Public API and CLI | A no-engine-link client performs the fixed-map loop over sockets |
| D. Generated geography | Fixed map is replaced by a validated seeded start; site/order/hash tests pass |
| E. Browser household | Character tabs, queues, map, tech, inventory, and events work with native forms plus progressive JS |
| F. Economy/progression | Research, construction, shipment, Favor, and recruitment complete the loop |
| G. PvM/raid | Parties, death/replacement, defence plan, and nonlethal raid defeat work |
| H. Hardening | External bot, replay, crash tests, two-household security tests, and curated demo are green |

The order can change, but do not begin with a sophisticated generator or polished tech-tree page before step A works.

## 5. Acceptance criteria

### Gameplay

- Three characters execute independent queues and remain patient when idle.
- A character can be ordered to travel, gather/mine, craft, research, and build.
- Gathering fills carried inventory, returning alone does not change camp stock, and `deposit` transfers it exactly once; equipment assignment and timed `rest`/`repair` work through public commands.
- Skill completion changes at least one visible action calculation.
- Three technology nodes enforce prerequisites and unlock real content.
- A storehouse and palisade have distinct mechanical effects.
- A shipment grants Favor exactly once; recruitment spends it exactly once.
- A zero-living, zero-Favor household can use the free emergency charter and receives exactly one newcomer after its virtual-time delay.
- A group expedition with 2–3 members can succeed or kill a character under deterministic fixtures.
- A holding raid can remove/damage permitted things but cannot permanently kill characters. Only eligible camp defenders participate, and ordinary running/pending work is neither cancelled nor silently refunded.
- An uncommanded character never selects its own task; executing a preassigned standing defence plan is not an autonomous choice.

### Lazy simulation and determinism

- Advancing from time 0 directly to the latest deadline gives the same result as advancing at every intermediate event; no empty per-tick loop exists.
- Same-time completion/cancellation and multiple completions follow documented stable ordering.
- Cancelled/replaced actions leave no effective stale completion.
- Polling frequently versus not polling does not change the generated world or command outcome.
- Fixed seed + fixed admitted commands/logical times + pinned rules produces identical events and canonical state hash, including agenda.
- Artificially tiny advancement budgets produce committed partial boundaries without skipping due events; continuation reaches the same hash as one unbounded advance, and commands wait for the target boundary.

### API and security

- CLI and bot link no engine and can discover rules through the API.
- Two accounts cannot read private state or mutate one another's household.
- Browser and bearer-token scopes are enforced server-side; secrets come from a CSPRNG and disabling an account revokes all sessions/tokens.
- Command retry and command-result recovery prevent duplicates, including rechecking IDs inside the writer and reconciling an unknown commit result before retrying.
- Stale revisions return a useful conflict rather than overwriting another tab's plan.
- Event paging/resync has no silent gaps.
- Essential browser commands work with native forms, not only JavaScript.

### Persistence

- Kill immediately before and after the commit boundary; recovery produces either the old or new committed state, never a hybrid.
- Running actions, future agenda, reservations, sequence counters, RNG state/derivation, and world revision survive.
- Snapshot + effect replay reconstructs state without resending commands/notifications.
- A new season has a new world ID; old commands/tokens cannot affect it.
- Account data survives; world power resets according to policy.

### Generator

- At least 100 test seeds yield a valid start after the documented bounded repair/fallback policy.
- Region/site generation order does not affect IDs, definitions, or hashes; an ordinary GET cannot trigger materialisation or agenda creation.
- Starter resources and wolf den are reachable.
- Persisted harvesting/building deltas survive reload/regeneration.

### Operational quality

- Core tests need no socket, PostgreSQL, or wall clock.
- API tests use real sockets and a disposable database.
- The external bot completes a multi-character soak with bounded failures and no privileged state.
- No ordinary simulation path runs unbounded work because of a malformed agenda or content definition.

## 6. Explicit exclusions

- WebSockets/SSE and graphical map client
- PvP, theft by other players, player markets, and co-op household permissions
- Genetic algorithms or any AI inside the server
- Needs/moods/lineage and autonomous character behaviour
- Infinite world, detailed erosion, weather fronts, full ecology, generated ancient history
- Multiple holdings, guilds, siege warfare, mounts, naval travel
- Separate Workshop building and searchable/filterable chronicle UI (the camp and basic pagination are enough)
- Large tech tree, item quality/durability, magic system
- Microservices, multi-writer worlds, region-sharded persistence
- Offline progression during host downtime
- Final game balance or monetisation design

## 7. Slice review questions

At the end, review recorded play rather than only checking features:

- Did a queue failure create an understandable replanning decision or just annoyance?
- Did any character's specialisation change how the player used them?
- Did the map cause a different route/order choice between seeds?
- Was there a satisfying reason to choose shipment versus construction versus expedition?
- Did the raid reward preparation without feeling like offline punishment?
- Was opening another character tab a useful workflow?
- Could a spectator understand the story from the chronicle?
- Did the bot find a degenerate strategy that trivialised the loop?

The answers decide the next content/system milestone. A larger simulation is not automatically a better one.
