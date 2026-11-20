# Ironwood Wilds — Procedural Generation (IronwoodWildsProcgen.md)

> How to generate a world procedurally, *and* how the generation itself
> stays procedural/deterministic/replayable. New lib: `libs/samterra`
> (terrain/worldgen math) + a content layer in `libs/samwilds`
> (prefabs, ecology, history passes).
> Companion: [Architecture](IronwoodWildsServerArchitecture.md) §2, §4.

## 1. Philosophy: world = pure function + player diffs

The map is never stored as a blob. It is:

```
world(seed, params)  ⊎  journal of player-caused changes
```

- `samterra_field(seed_layer, x, y)` — any number of seeded scalar
  fields (elevation, rainfall, temperature, ore affinity, …) — pure,
  deterministic, cheap, cacheable per tile. This is samsimulator's
  "two layers" rule imported wholesale: geographic fields are functions;
  only things with *history* are state.
- **State is only what players (or ecology) changed**: felled trees,
  mined veins, buildings, paths, corpses. Those are entities in the ECS,
  born as journal events — which means the snapshot is small, the
  journal *is* the map's history, and any past moment reconstructs.
- Consequence: a season's whole world costs a seed string + params dict
  + the journal. Backups, forks ("what if the wolf pack had won?"),
  timelapses, and GA fitness runs all read the same two artifacts.

## 2. Layered LOD — generate lazily, freeze on touch

V1 world: **512×512 tiles, 8×8 regions of 64×64**. Generation is
hierarchical and *lazy* — the same philosophy as the time model applied
to space (don't pay for what nobody looks at):

| Layer | Generated when | Cost |
|---|---|---|
| Planet/climate skeleton (region tags, biome bands, coast) | season start | once, seconds |
| Region tile detail (terrain, hydrology, deposits, flora) | first contact (anyone adjacent/explores) | per-region, ms |
| Site interior (mine levels, ruin rooms) | first entry | per-site, ms |
| Names & descriptions | on first render, then frozen | trivial |

"Freeze on touch": once generated, a region/site becomes ordinary cached
state (revision-stamped for client caching) — it never re-rolls, and all
subsequent changes are journal events like any other. Lazy also protects
the sim: worldgen work happens on the sim thread's schedule inside a
tick (budgeted; a burst of exploration spreads over ticks), never as a
blocking surprise.

## 3. The pass pipeline (worldgen as systems — again)

Worldgen is not a monolithic function; it is an ordered pipeline of
**passes**, and passes are just the Architecture §5.2 systems run in a
special mode (`worldgen` mode) over the same ECS. This gives procgen the
same extensibility as gameplay — a season gimmick ("the boggy season")
is a pass toggle in the season params — and it sets up the deepest idea
in this doc (§5): later passes *simulate*.

Region-detail pass order (v1):

```
tectonic-lite → elevation FBM → hydrology (flow to sea) → drainage/wetlands
→ soil/fertility → biome classification (Whittaker) → flora scatter
→ mineralization → beast ecology seeding → site placement (ruins/camps)
→ claim-suitability scoring (server's "where can households land")
```

## 4. Technique menu (per layer, with the why)

| Concern | Technique | Why this one |
|---|---|---|
| Elevation/terrain | domain-warped FBM over value/simplex noise (integer-seeded) | cheap, art-directable via warp; no physics pretence needed at 64px regions |
| Mountain spines | tectonic-lite: 5-9 seeded "plates" (Voronoi) + uplift along boundaries | makes ranges *linear* and world-legible; pure noise makes blob soup |
| Rivers | steepest-descent flow accumulation over elevation, then carve | gives real drainage basins (fertility, travel, mills follow logically) |
| Biomes | Whittaker diagram (temp × moisture) + soil modifier | textbook, data-driven, trivially season-tweakable |
| Flora/deposits | blue-noise scatter (Bridson Poisson-disk) per biome, density from fields | even-but-organic placement; no clumping artifacts; density knobs per season |
| Site interiors | BSP room-and-corridor for ruins/mines; cellular-automata caves for deep levels | the two classic legible generators; WFC deferred until a shape demands it |
| Beast packs | scatter + per-biome ecology tables + a migration pass | PvM content that varies per region and season |
| Names | syllable CFG with curated low-fantasy lexicons, region-seeded | "Hollowmere", "Bram's Fall" — names that feel GoT-adjacent, not random-syllable soup; grammar is data (per-culture tables later) |
| Region descriptions | procgen_mvp's repository-of-things (YAML, category slots) when it exists | its designed purpose; the map's prose layer without inventing a second system |

Numbers-first discipline (from samsimulator, and it applies doubly to a
game): every pass emits **statistics** (ore tiles per region, forest
cover %, river-frontage per household slot), and season creation gates
on them — "iron within ±15% of target density", "every landing slot has
fresh water within 8 tiles". A map that fails gates re-rolls
(seed-derived next candidate) before anyone ever sees it. This is
samgrognard's gate-evaluator idea, repurposed as **map QA**.

## 5. Generation by simulation (the emergence fan-service, and good design)

The best procgen isn't noise — it's *history*. Because passes are ECS
systems, later passes can run **agents** over the earlier passes' world:

1. Ancient-homestead pass: seed 30-60 iron-age households, run a
   coarse "live, expand, fight, abandon" simulation for K abstract
   years → ruins, fields-gone-wild, old battle sites, named ghosts in
   the lexicon. (Coarse = aggregate scalars, not per-person ticks —
   samsimulator's herd-scalars rule: never per-head state where a
   number will do.)
2. Beast-ecology pass: packs placed by prey density; a few seasons of
   abstract predator-prey drift → believable pack territories and one
   or two "why is nothing living here" regions with a *reason*.
3. Eventual: the previous *season's* journal, coarse-summarized, seeds
   legends in the next season's worldgen ("the Oakfleet landing failed
   here") — seasons that remember each other without persistent maps.

Costs are bounded (all integer math, K small), determinism is
unaffected (same seed ⇒ same history ⇒ same ruins), and the payoff is
the Dwarf-Fortress thing Sam actually loves: a ruin you can *tell* had
inhabitants, because it did.

## 6. Discoverability & fog (why procgen is also the PvM difficulty curve)

Unexplored tiles are fogged in reads (scope rules in the events/view
layer). Exploration is an action (samvillage §4.10 generalized from a
fixed site list to the real map): a person walks and reveals radius.
Because site interiors generate on first entry, distance-from-landing
becomes the difficulty dial — near the coast: shallow mines, small
game; deep wilds: rich veins, big packs, intact ruins. The tech tree's
expedition line (sledges, wagons, camps) is what makes distance
survivable — procgen, economy, and tech interlock by construction.

## 7. Determinism, versioning, testing

- Every layer versioned (`SAMTERRA_FIELD_VERSION`, per-pass versions);
  all versions + seed + params recorded in the season row. A snapshot
  refuses to load under mismatched worldgen versions (samvillage's
  save/rules gate).
- Golden seeds: fixed seeds whose region maps hash to committed values
  (samvillage §9.2 pattern) — noise refactor? The goldens catch it
  same-day.
- Statistics gates in CI (§4) — density regressions fail the build, not
  the season.
- Eyeball tool: `apps/samwilds-cli map` (or a tiny spike) renders any
  seed to ANSI/PNG with overlays (biomes, ore, sites, landing scores) —
  one-Friday tool that pays for itself every season tuning.
- Field functions are pure and side-effect-free → trivially unit
  tested per-tile; region pipeline tested per-pass on fixtures.

## 8. What procgen does NOT decide

Biomes don't set Charter values, durations, or tech prices (rules
tier-1 data does — Architecture §6). Worldgen proposes the map's
*opportunities*; the rules tables define what they're *worth*. Keeping
those separate is what makes "tune the season economy" a data edit and
"tune the world shape" a params edit — two dials that don't touch.

## 9. Decision log

| # | Decision | Note |
|---|---|---|
| P1 | World = seed+params pure fields ⊎ journal diffs | tiny saves, free forks/timelapses, samsimulator rule |
| P2 | Lazy hierarchical LOD; freeze on touch | exploration costs scale with players, not map size |
| P3 | Worldgen = pass pipeline over the same ECS/systems | one extensibility mechanism, season gimmicks are data |
| P4 | Simulation passes for ruins/ecology/legends | emergence as content; bounded, deterministic |
| P5 | Statistical gates + golden seeds + map render tool | map QA like samgrognard gates; tuning loop is visual |
| P6 | Fog + first-entry interiors as the difficulty curve | procgen interlocks with tech/economy by design |
| P7 | Names/descriptions via seeded grammars (+ procgen_mvp later) | prose without AI, data not code |
