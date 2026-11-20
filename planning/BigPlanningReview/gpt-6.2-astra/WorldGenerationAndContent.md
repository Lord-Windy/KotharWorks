# World generation and content — make geography do some work

**Generate the cost of living in a place before generating its description.** “Iron is on a wet bank across a long trail; good straight timber is close to camp” can change what a household builds. Twenty interchangeable sites with different names won't. For a text world, start with a small, weighted graph of places, not a rendered terrain engine.

## From a seed to a playable charter

At founding, `samwildsgen` takes a 64-bit seed, an immutable catalog and a profile (`river_frontier.v1`). It returns a finite **baseline world definition** and a diagnostic report. Everyone in the season sees the same land; the initial survey names the three starter sites. The [prototype](FirstExpedition.md) also has a hand-specified world in the same format so we can reason about timings without claiming to know what an unbuilt generator produces.

| Pass | Proposed first algorithm | Result used by play |
|---|---|---|
| 1. Terrain | Lay out a bounded 5×5 survey grid. Sample a few seeded coarse height/moisture values, interpolate with integer weights, and give cells stable coordinate IDs. Choose the low, wet corridor as the stream; classify nearby ground as bank, woodland or usable terrace. | Terrain tags and positive integer crossing costs. The grid is generation scratch; people navigate named places and paths. |
| 2. Place the necessities | Enumerate plausible triples: a dry landing near water, a wet/exposed bank for ironstone, a wooded stand for alder. Score by habitat fit and charter distance bounds, breaking ties by a keyed seeded ranking then coordinates. | Distinct home, ore and timber sites. The home gate and each household's cache are at the landing; no invisible teleport from mine to cache. |
| 3. Connect and stock | Find least-cost walkable paths from landing to bank and stand (Dijkstra, stable coordinate tie-break). Collapse each path to a graph edge with a travel time. Derive reserve by site archetype and local terrain. | Two bidirectional routes. Working quantities and slots are **per household** in v0; one household's extraction cannot empty another's starter face. Baseline geography and travel costs remain shared. |
| 4. Prove the charter works | On the actual graph and catalog, check reachability and resource/skill/recipe constraints from **empty stores, starter kit, two adults, three bunks**. Ore is 20–40 s one way, timber 15–30 s; a household can haul ≥6 iron and ≥3 timber without prior unlocks. Work a legal path through one shed **and** two shipments. | The player can make the central choice and, given time, recover the other option. A map with both resources behind the very technology they unlock fails. |
| 5. Name and describe | Choose names and short clauses from biome and site tags using a naming-only random stream. Attach inspectable facts such as “bank, 30 s from landing, yields a two-unit load”. | Text explains the underlying costs. “Greybank” is a display label; commands refer to a stable site ID. |
| 6. Seal the result | Write an ordered list of sites/edges/archetypes, initial allotments, the manifest and a digest of the canonical map. Report shortest paths, starter quantities, attempt count and any fallback. | Live server loads this exact baseline. Lab can compare costs across seeds. A later generator upgrade can't move someone's mine. |

Bound the search: try at most eight deterministic regional candidates derived from `(region, attempt)`. If none pass, install a small **authored, validated river-clearing motif**, mark the fallback and validate it too. If even it fails under the selected catalog, refuse to open that season with the failed predicate. Infinite rejection loops and silently issuing free iron would both mask a bad generator. Test fallback deliberately, then watch how often ordinary seeds need it.

## Determinism that survives content edits

- Derive stage seeds from a fixed byte encoding of `(world_seed, stage_tag, stage_version, region_id, feature_key, attempt)`. Example convention: length-prefixed UTF-8 strings and fixed-width little-endian integers, FNV-1a-64 for this **non-secret** seed derivation, then the integer draws of [SamRng](../ptah/libs/samdata/include/samdata/samrng.h). Pin the algorithms and a few input/output vectors. Use integer weights and unbiased bounded draws. Never seed from process time, pointer values or table iteration order.
- Give `terrain`, `placement`, `quantity` and `names` distinct streams. A site's quantity draw is addressed by its logical site key. Adding ten names must not move a seam or change its reserve. Record the full pack digest for identity; don't feed a wording-only pack change into every stage seed. Use explicit feature slots/coordinates for IDs rather than hashes of their display names.
- Keep generation randomness apart from simulation randomness. A later PvM draw can be addressed by `(world_seed, encounter_id, attempt_no, outcome_channel)` with its own version. Inspecting a site or re-rendering the chronicle consumes **no** draws. No need to serialize opaque RNG pointers.
- The season manifest pins seed, generator/profile versions, rules/pack digest, engine revision, map encoding version, baseline digest and chosen attempt/fallback. Store the baseline bytes **as well as** the seed: recovery reads the map, verification regenerates and compares it. A live player's view gets discovered geography, not the seed or unrevealed encounters.

## What is generated, what changes

| Layer | Example | Lifetime |
|---|---|---|
| Authored catalog | “An alder work action yields 3 timber; Field Carpentry needs a full timber delivery.” | Immutable for a season; reusable across seeds. |
| Generated baseline | `s2` is an alder stand, a 20 s walk, with a starting timber allotment of 18. | Generated once at founding. A private allotment starts at that defined quantity on first use. |
| Mutable overlay | Kit has claimed 3 of those 18; after completion 15 remain unclaimed and Kit carries 3. A haul shed shortens this household's next trip. | Stored transactionally as household/site stock, claims, cargo, discoveries and building state. No regeneration on read. |
| Observation | “Alder Stand · 15 timber · 1 free work slot · ten seconds by your shed trail.” | Derived from the current viewer's knowledge and state. Names don't own mechanics. |

For a larger frontier, generate additional regions only when needed. Derive both sides of a boundary crossing from the **ordered pair of region coordinates** so either exploration order yields the same river road. Evaluate hydrology/major routes at the larger regional scale first, then fill in local sites. Persist discoveries and depletion separately. Finite mineral faces stay depleted; future woodland regrowth can have a next regrowth deadline or a capped closed-form gain since last harvest. Geological ore needn't respawn because someone refreshed a page. Add a second route, seasonal flooding or a distant rich seam when it creates an observable logistics choice.

## How Sam adds an afternoon's worth of content

Use versioned JSON packs, decoded once into immutable typed tables and validated before a world opens. IDs are stable and namespaced (`item.ironstone`, `gather.ironstone`, `tech.field_carpentry`); translations, prose and numeric IDs used inside the engine can change independently. V0 needs items, site archetypes, work prototypes, tech predicates, building effects, shipping contracts and recruit charters. Keep predicates/effects a short typed vocabulary, e.g. `lifetime_banked_at_least`, `requires_tech`, `shorten_home_leg`. No new scripting language is needed.

**Growing the tech tree:** the first node, Field Carpentry, grants a haul-shed design; the player must still build it. Later it can branch to Logistics (better freight) or Joinery (sheltered stores). Masonry **and** Ironworking might grant the gatehouse design, which still needs a skilled mason and actual fittings to construct. Keep permanent knowledge, current facilities, a person's skill and consumable costs as four distinct checks. V0 folds making simple fittings into the shed's work time; making ore → bloom → fittings a separate chain later changes labour allocation without changing how commands are submitted.

**Example content-only addition:** introduce rush cutting as another use for a wet bank. Define `item.rush`, give marsh-bank archetypes a rush allotment, and add this work row and a basketry design that uses its output. This row illustrates the proposed authoring format:

```json
{
  "id": "gather.rushes", "verb": "gather", "label": "Cut rushes",
  "site_tag": "marsh_bank", "skill": "foraging", "min_level": 1,
  "duration_s": 75, "output": {"item": "item.rush", "qty": 3}, "xp": 5
}
```

The existing gather strategy claims the local stock and pack space, sets the deadline, then moves rushes into cargo. The action picker and bot both discover the row via the visible catalog; the chronicle uses its label and actual transfer. Adding a **new mechanic**, e.g. a party sharing one return deadline, instead requires a typed verb/effect handler, an observation field and a behavioural fixture. Register it once, then author many encounters using it. Neither change needs a separate HTTP endpoint per material or monster.

**Publish a pack:** validate unique IDs and references, positive durations/yields, supported effects, bounded integer arithmetic, valid skill thresholds and an acyclic tech prerequisite graph. Check *economic* reachability as well as graph validity. Preview a cohort of seeds as text; keep the histogram of starter walk times, reserve ranges and fallback count. Retain one awkward seed. Proposed generator gates: identical canonical map for repeat runs and reordered enumeration; a name-only edit leaves topology/yields identical; all of a fixed 100-seed cohort pass the charter proof; at least two distinct cost layouts appear. Archive the accepted pack and use it for **new** seasons. These are proposed tests, not results.
