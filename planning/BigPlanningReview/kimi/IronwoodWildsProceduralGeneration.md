# Ironwood Wilds — Procedural Generation Plan

## 1. Requirements

Generation must produce more than a noisy map. It must create understandable
places, viable starting positions, resource decisions, dangerous excursions, and
room for later ecology. It must also satisfy technical constraints:

- identical inputs produce identical output regardless of request order;
- opening region B before region A does not change either;
- the server can materialise only what players approach;
- regenerated base terrain never overwrites player modifications;
- every generated world records seed, generator version, and content/rules
  digest;
- tests can inspect invariants without using the web UI;
- descriptions are derived from actual state.

## 2. Spatial model

Use a **large bounded world generated on demand**, not an infinite world.
Boundedness helps season archival, threat placement, and world-level validation;
lazy materialisation avoids generating everything at start.

```text
World
  └── Region (integer region coordinate)
        └── Tile (integer local coordinate)
              └── zero or more Sites (stable generated ID)

Holding, character, expedition, and monster positions reference these IDs.
```

A region's grid dimensions belong to the ruleset; 64×64 is an experiment, not a
sacred value. The vertical slice can materialise one small region while
retaining the same coordinate types.

Two levels of location are useful:

- **Tile:** terrain, elevation, moisture, biome, movement cost, broad ecology.
- **Site:** concrete interaction target at a tile, such as `iron-seam-…`,
  `wolf-den-…`, or a household holding.

Characters can initially move between sites/tiles through deterministic routes.
No continuous coordinates or physics are required.

## 3. Versioned generation pipeline

Each stage has explicit inputs and outputs and a separately namespaced random
stream.

```text
World parameters
  -> 1. Continental/region elevation
  -> 2. Temperature and prevailing moisture
  -> 3. Drainage, rivers, lakes, and wetlands
  -> 4. Soil and biome classification
  -> 5. Routes and traversability
  -> 6. Resource deposits and vegetation
  -> 7. Wildlife and monster territories
  -> 8. Sites, ruins, and landmarks
  -> 9. Candidate household starts
  -> 10. Validation and repair
  -> materialised region artifact
```

Do not implement all stages at full depth immediately. The important decision is
that later stages consume facts from earlier stages rather than placing
unrelated sprites on noise.

### 3.1 Elevation

Start with coordinate-based multi-octave noise plus broad regional masks. Unlike
diamond-square, coordinate noise has no chunk-boundary or generation-order
dependency. Add domain warping only if the basic output is too regular.

For authoritative cross-platform output, prefer integer/fixed-point noise and
rounding. If floating-point noise is used during an experiment, generated output
must be stored and hash-tested on supported platforms before it becomes a
compatibility promise.

### 3.2 Climate

Derive temperature from latitude/world band, elevation, and optionally
prevailing winds. Derive moisture from broad noise, distance to water, and
rain-shadow effects. These do not need meteorological accuracy; they need
plausible, visible relationships.

### 3.3 Drainage and water

A practical progression:

1. Compute depression-aware flow directions from each tile to a lower neighbour.
2. Accumulate upstream flow in descending elevation order.
3. Mark rivers above a threshold and lakes/seas at boundaries or filled
   depressions.
4. Classify banks, marshes, and crossings.
5. Use the resulting water network for biome, soil, movement, resources, and
   site placement.

Full erosion is a later enhancement. A small amount of thermal/hydraulic
smoothing can remove noise artifacts, but it should not block the first map.

Coordinate noise makes elevation continuous across regions, but **does not by
itself make drainage chunk-independent**. At Level B, generate a cheap
world-scale coarse elevation/drainage field once at world creation, then refine
it inside each region using that field and a deterministic neighbour halo.
Boundary refinement must be a pure function of a shared border/catchment ID and
symmetric halo inputs: independently generating either side must produce the
same flow. Persisting an artifact is only caching; it must not bless whichever
side happened to generate first. If a stage cannot satisfy that rule, it belongs
in the upfront world-coarse pass or a deterministic multi-region writer job, not
an on-demand local callback. The Level A slice uses region-local drainage only.

### 3.4 Soil and biome

Biome is a classification of elevation, temperature, moisture, drainage, and
soil—not another independent random roll. Example: high, cold, dry tiles become
exposed highland; wet lowland near slow drainage becomes marsh; moderate,
fertile lowland becomes meadow or broadleaf forest.

Soil fertility, drainage, and forest cover become simulation fields. This lets
farming, regrowth, and weather alter the same facts used for generation and
description.

### 3.5 Routes

Movement costs come from slope, biome, water, vegetation, roads, and weather
modifiers. Rivers need crossings or costly fords. Validate connectivity before
accepting a region.

Use deterministic A\* with a documented neighbour order and coordinate
tie-break. Two equal paths must not depend on hash-map iteration.

### 3.6 Resources and sites

Resource placement follows host conditions:

- ore follows geology/elevation bands;
- useful trees follow biome, age, and moisture;
- herbs follow light/water/soil combinations;
- clay follows old water deposits;
- prey follows forage and water;
- predators follow prey and cover;
- ruins follow a later, sparse settlement/history layer.

Each generated site receives a stable key derived from world/region/tile/local
slot, not from the order in which maps were requested. Site definitions describe
capacity, richness, depletion, recovery, danger, and allowed actions.

### 3.7 Monster territories

Monsters need territory and ecological roles rather than uniformly random
spawns. A wolf den can require cover, prey, and distance from starting holdings.
A ruin guardian can be tied to a ruin tier. Danger should generally increase
with distance from safe starts, but local exceptions create useful exploration
stories.

Spawning schedules and population changes are runtime state. Generation stores
initial conditions, not an unchanging promise that every monster remains
forever.

Routes and territories crossing a region border use the same rule as drainage: a
shared coarse route/territory ID and symmetric border inputs determine both
sides. A route endpoint or territory owner cannot be selected from a differently
ordered neighbour list.

## 4. Randomness without generation-order bugs

Do not draw every random value from one global mutable `SamRng`. That makes the
map depend on which region or site happened to be generated first.

Derive independent streams from stable coordinates and purpose:

```text
stream_seed = Hash64(
  world_seed,
  generator_version,
  stage_tag,          // elevation, vegetation, monsters, start sites, ...
  region_x, region_y,
  tile_x, tile_y,
  local_slot,
  attempt
)
```

Then create a local `SamRng` from that value or use a documented counter-based
hash for individual rolls. The exact hash is a format decision and must remain
stable for that generator version.

Runtime random events use a different namespace. For example, combat rolls can
derive from `(world_seed, "combat", expedition_id, round, roll_index)`; loot can
derive from `(world_seed, "loot", expedition_id, encounter_id)`. Systems cannot
accidentally consume one another's rolls.

## 5. Lazy materialisation and persistent deltas

For each region, separate three things:

1. **Generation identity:** world seed, generator version, rules/content hash.
2. **Base artifact:** deterministic terrain, initial sites, and initial
   populations.
3. **World delta:** harvested amounts, regrowth timestamps, destroyed sites,
   discovered state, roads, holdings, moved monsters, and every later mutation.

“First access” means a **world-writer materialisation request**, not an ordinary
GET. Household creation, a movement/expedition command approaching the region,
or an administrative writer job can request it. Generation, base-artifact
persistence, delta application, ID allocation, and any agenda creation commit as
one world mutation. A read-only map request either sees the committed artifact
or receives `REGION_NOT_MATERIALISED`; it never generates state on a transport
worker.

Persist the base artifact for each materialised region. A cheap regenerable
in-memory cache is still useful for tests, but a long-lived snapshot then
contains all materialised base facts plus deltas and does not depend on old code
to restore them. Unmaterialised regions remain represented by seed/version and
require the world to retain a compatible pinned generator until they are
materialised; a newer binary must never silently reinterpret them.

The Level A slice materialises its one starting region at world creation. Later
static regions may be materialised on approach, but **no new autonomous agenda
entries are backdated**. Their dynamic schedules begin at the materialisation
logical time under an explicit dormant-to-active rule. Resource regrowth in the
slice is created only by an actual harvest, so an unvisited static site has no
hidden event history. A later system needing pre-existing global effects must
either pre-register those effects at world creation or derive them by a
side-effect-free closed-form function; it must never replay months of newly
discovered active-region events retroactively into an already advanced world.

Discovery/visibility is per household and is not part of public base terrain. A
household's map observation joins committed public/generated facts with its own
discovery records.

## 6. Starting locations

A random legal tile is not necessarily a fun start. Score candidates using hard
gates and soft preferences.

### Hard gates

- reachable land area above a minimum size;
- fresh water and basic food/wood within a small travel budget;
- at least one accessible early resource chain;
- room for a holding;
- outside high-tier monster territory;
- minimum separation from other starting holdings;
- no isolated one-tile island unless intentionally supported.

### Soft preferences

- mixed terrain rather than a uniform patch;
- several route choices;
- visible future resources beyond the immediate safe area;
- moderate defensibility;
- interesting landmark nearby.

Run a bounded number of seeded attempts. If none passes, repair the best
candidate by placing guaranteed starter resources and clearing danger. If
validation still fails, use a known-safe template with seeded variation. Never
loop forever waiting for a perfect map.

Multiple households in one world need allocation under the single world writer
so two households cannot claim the same start simultaneously.

## 7. Text generation from facts

The engine should emit structured observations such as:

```text
terrain=forest, canopy=dense, moisture=high, slope=gentle,
water=nearby_stream, trees=[ironwood, ash], danger=low,
visible_sites=[old_coppice], household_knowledge= surveyed
```

A renderer turns these into prose:

> A dense stand of ash and dark-barked ironwood climbs a gentle slope. A narrow
> stream runs somewhere below the eastern edge. The old coppice shows signs of
> careful cutting, though no one has tended it recently.

Use templates, adjective tables, and deterministic selection. An LLM may be an
optional offline flavour experiment later, but it is not needed, must not decide
game facts, and would make replay and client equality harder.

For chronicle entries, prefer clear causal text over purple prose: who acted,
where, what changed, why an action failed, and what happens next.

## 8. Depth roadmap

### Level A — vertical slice

- one bounded region;
- elevation/moisture noise and a small biome table;
- passability and deterministic travel cost;
- placed grove, mine, den, and safe holding sites;
- validated start and basic textual map;
- seed stability and reachability tests.

### Level B — coherent world

- multi-region bounded map;
- drainage/rivers/crossings;
- climate and soil;
- conditional resources and wildlife;
- lazy artifacts plus deltas;
- candidate-start scoring and repair.

### Level C — living environment

- weather fronts and seasonal modifiers;
- plant growth, harvesting pressure, and succession;
- prey/predator populations and migration;
- roads and holdings changing local risk;
- ecological events with explicit scheduled updates.

### Level D — deep history

- predecessor settlements and ruins;
- generated regional names and historical relationships;
- geology/erosion enhancements;
- world-level archival summaries.

Do not start at Level D. Real player history is likely to be more compelling
than thousands of generated ancient battles.

## 9. Runtime environmental simulation without fixed ticks

Deep ecology does not require per-second world updates. Schedule coarse
transitions:

- forest cohort re-evaluated next game day;
- weather front arrives at a known logical time;
- prey population recalculated next ecology interval;
- resource node regrows at a specific timestamp;
- monster patrol reaches its next territory node at a specific timestamp.

When a region is advanced to time `T`, process its due events in stable order.
To avoid a huge catch-up loop after a long absence, supported systems need
either closed-form elapsed-time calculations or capped/coalesced intervals. Any
cap changes game rules and must be visible, not a hidden optimisation.

Initially advance the whole world to one `T`; split region calendars only when
profiling justifies the cross-region complexity.

## 10. Validation and tooling

Every generator stage should have headless tests and inspectable output.

### Property tests over many seeds

- identical seed/config yields identical region hash;
- generating regions in shuffled order yields the same hashes;
- no invalid biome/terrain combinations;
- all mandatory starter resources are reachable within their allowed budgets;
- no starting site overlaps forbidden terrain or high-tier danger;
- generated IDs are unique and stable;
- path neighbour ordering is deterministic;
- maps stay within numeric and coordinate bounds.

### Content tests

- each biome has at least one viable description;
- every site action references a registered action;
- resource chains needed by starter technology exist in reachable form;
- monster tiers reference valid encounter tables;
- no unreachable mandatory progression node is assumed to be universally
  available without an alternate path.

### Human inspection

A `samwilds-lab` or generator command in the tools app should eventually render
ASCII maps, elevation/moisture layers, route overlays, site tables, start
scores, and hashes. This is more useful than viewing one polished map because it
exposes systematic generation failures across hundreds of seeds.

## 11. Generation acceptance criteria for the slice

The generator is ready for the vertical slice when:

1. the same command journal against the same generated world produces the same
   final state hash;
2. 100+ test seeds produce at least one valid start after the documented repair
   policy;
3. the starter camp, wood source, ore source, and first monster target are
   reachable;
4. opening the same sites in different orders does not change their definitions;
5. a saved modification survives base regeneration/reload;
6. a browser and a bot receive equivalent structured site facts, even if their
   text presentation differs.
