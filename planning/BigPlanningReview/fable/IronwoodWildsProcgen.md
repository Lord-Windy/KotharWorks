# Ironwood Wilds — Procedural Generation, Procedurally

**Status:** proposal v0.1 · 2026-11-20 · author: Fable **Scope:** how the land,
its names and its prose are generated — and how the generator itself is built
step by step and kept under test. **Relates to:** `planning/procgen_mvp.md`
(your text-generation idea, which this gives a first consumer) and the "heavy
procedural generation" clause of ticket `60eb0b1b`.

______________________________________________________________________

## 1. Summary

"Procedurally" twice over. The world is made by procedure — and so is the
generator: a fixed pipeline of small pure passes, each fed by tables, each
testable alone.

| #   | Idea                        | In one line                                                                       |
| --- | --------------------------- | --------------------------------------------------------------------------------- |
| 1   | **The land is a function**  | `region = generate(seed, pack, rx, ry)`. Untouched land is never stored           |
| 2   | **A pipeline of passes**    | Each pass reads only the passes before it                                         |
| 3   | **Keyed randomness**        | Every choice is keyed by what it is for and where — never by the order things ran |
| 4   | **Constraints with repair** | A playable start is guaranteed by rule, not hoped for                             |
| 5   | **Generation under test**   | Golden seeds, and gates over a thousand seeds, exactly like the soak gates        |

## 2. Two layers: the land, and what happened to it

|          | The land                                                         | What happened                                                         |
| -------- | ---------------------------------------------------------------- | --------------------------------------------------------------------- |
| What     | Sites, routes, what each node can hold, names, base descriptions | Stock taken, structures built, lairs cleared, who has discovered what |
| Lives in | Nowhere. Recomputed from the seed on demand, then cached         | World state and snapshots                                             |
| Changes  | Never, within a world                                            | By events                                                             |

Your samsimulator notes reached the same split: pure seeded fields, plus parcels
carrying use and history.

What it buys:

- **Small snapshots.** A snapshot holds differences from the land, not the land.
- **A world with no edge.** A region exists the moment something first touches
  it.
- **Inspectable.** Any region of any world can be regenerated on a laptop from
  two numbers.

One rule protects it: **the generator changes only between seasons.** Its
version is part of the rules digest, and each touched region's hash is stored in
the snapshot, so a generator that drifted is caught at load rather than
discovered by a player whose forest moved.

## 3. The world model

```
  world ── regions ── sites ── nodes        the graph: what the rules use
              │          └──── routes
              └── tiles ── fields           the grid: how it looks, what it costs
```

- **Tiles** are a square lattice carrying fields: elevation, moisture, wildness.
  They exist for appearance and for costs.
- A **region** is a window of tiles — 40 × 12 in the example below; the size is
  a pack constant — holding eight to fourteen sites.
- **Sites** are places with a name: a stand, a seam, a ford, a lair.
- **Nodes** are what can be gathered at a site.
- **Routes** join sites and carry a travel time.

**A grid for looks, a graph for rules.** The engine never asks what is at tile
(17, 4). It asks how long it takes to walk from one site to another. Your
Biofabrica notes made the same call — "movement is zone-to-zone only".

**The engine knows only the graph.** How a graph is made is the theme's
business, so the generator arrives with the pack:

```c
typedef struct SamWorldGenOps {
  uint32_t version;
  /* Pure: the same inputs give the same region, every time, in any order. */
  bool (*region)(uint64_t seed, int32_t rx, int32_t ry, Samrena *arena,
                 SamWorldRegionDesc *out);
  /* Prose for one site, at a depth, given what has happened there. */
  void (*describe)(const SamWorldSiteDesc *site, const SamWorldSiteState *state,
                   uint32_t depth, SamStringBuilder *out);
} SamWorldGenOps;
```

A space pack would return orbital zones and jump routes through the same two
functions.

## 4. The pipeline

| #   | Pass                  | Reads                             | Produces                                       | Tables from the pack                   |
| --- | --------------------- | --------------------------------- | ---------------------------------------------- | -------------------------------------- |
| 0   | **Fields**            | seed, (x, y)                      | elevation, moisture, wildness: integers 0–1023 | octaves, scales                        |
| 1   | **Biomes**            | fields                            | a biome per tile                               | elevation band × moisture band → biome |
| 2   | **Region frame**      | region coordinates                | shore or wild; danger tier                     | —                                      |
| 3   | **Sites**             | biomes, tier                      | 8–14 sites: kind and tile                      | site kinds by biome and tier, weights  |
| 3b  | **Quotas and repair** | sites                             | the same, with guarantees met                  | what a region must contain             |
| 4   | **Nodes and lairs**   | site kind, tier                   | what each site holds                           | per-kind node tables                   |
| 5   | **Routes**            | tiles, sites                      | edges with travel times                        | step cost per biome                    |
| 6   | **Landings**          | shore region                      | Family plots, each with private starter nodes  | plot count, spacing, fairness bounds   |
| 7   | **Names**             | kind, biome                       | "Hollin Stand"                                 | name grammars                          |
| 8   | **Descriptions**      | all of the above, plus live state | prose at three depths                          | templates, pick-lists                  |

**Pass 0 — Fields.** Integer value noise: hash the lattice corners with the
keyed RNG, blend with a fixed-point smoothstep, sum a few octaves. Fields are
global functions of (x, y), so two neighbouring regions agree along their border
without any stitching. No floats — the generator obeys the same integer law as
the engine.

**Pass 2 — Region frame.** A region is either a **shore** (it has landing plots)
or **wild**. The choice is a keyed function of the region's coordinates,
arranged so every shore has wild neighbours. A wild region's tier is its
distance from the nearest shore, nudged by the wildness field. That is the whole
of "farther is richer and worse".

**Pass 3 — Sites.** A jittered grid: split the region into cells; in each, a
keyed roll decides whether a site exists, where inside the cell it sits, and —
from a weighted table conditioned on the biome under it — what kind it is.

**Pass 3b — Quotas and repair.** A shore region must contain at least two timber
sites, one stone site and one ore site, each within a stated walk of every
landing. If a quota is unmet, the nearest suitable site is converted, in a fixed
order. The number of repairs is recorded as a metric, because a generator that
repairs often has wrong tables (gate G5).

**Pass 5 — Routes.** Join each site to its two or three nearest; add edges until
the graph is connected. A route's travel time is the sum of step costs over the
tiles it crosses — meadow cheap, forest dearer, crag dear, water only at a ford.
Later, a built road is a structure that lowers one edge's cost: infrastructure
that emerges from play.

**Pass 6 — Landings.** A shore region holds several plots, placed on open ground
near water and spaced apart. Each plot gets its own starter nodes (the "nodes at
your landing are yours" default). Families are assigned plots in founding order
— and founding is a journaled command, so allocation replays exactly.

## 5. Keyed randomness

```c
/* One stream per purpose and place. The order of generation cannot matter. */
static inline uint64_t gen_roll(uint64_t seed, uint32_t pass,
                                int32_t rx, int32_t ry, uint32_t index) {
  uint64_t place = ((uint64_t)(uint32_t)rx << 32) | (uint32_t)ry;
  return samrng_keyed(seed, pass, place, index);
}
```

| Property                                   | Why it matters                                             |
| ------------------------------------------ | ---------------------------------------------------------- |
| Regions can be generated in any order      | Laziness: a region is built when first touched             |
| Adding a pass does not disturb the others  | Next season's lairs do not reshuffle this season's forests |
| A pass can be rerun alone                  | Debugging one layer without the rest                       |
| The same function the engine uses for dice | One mechanism, tested once                                 |

## 6. Text

No AI at run time — the rule from `procgen_mvp.md` stands. Text comes from
tables, composed by rule, and is stable: the same site reads the same way every
time unless something about it has changed.

### 6.1 Names

A small grammar per kind of thing.

```
site.woods  :=  "{tree} {stand}"  |  "the {adj}wood"  |  "{house}'s {stand}"
tree        :=  Hollin | Ironbark | Greyleaf | Blackthorn
stand       :=  Stand | Hanger | Shaw | Deeps
adj         :=  Grey | Old | Far | South
site.seam   :=  "{colour}cap Seam"  |  "{house}'s Delve"
```

Giving *Hollin Stand*, *Greywood Deeps*, *Redcap Seam*, *Southwood*. Settlers
take a given name from a pool and their House's name.

### 6.2 Descriptions

The `procgen_mvp.md` model — templates, slots and pick-lists — at three depths.

| Depth       | Shown                     | Example                                                                                                                                               |
| ----------- | ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Glance**  | In lists and on the map   | *Hollin Stand — ironwood, four minutes west.*                                                                                                         |
| **Look**    | On arriving               | *A close stand of ironwood crowds the western slope. The trunks are grey as slate and wider than a cart. A stream runs along the foot of it.*         |
| **Examine** | On asking about one thing | *The nearest trunks ring like stone under a knuckle. Fresh stumps show where someone has been felling; perhaps half the stand is still worth an axe.* |

The last sentence is **derived from state**: the node is at half stock, and it
was another House that took it. That is where a text game gets its sense of a
shared, lived-in world — for the price of a few conditional clauses per site
kind.

Rules:

- Each pick is keyed by site id and slot, so prose is stable.
- State clauses are computed from live values at view time.
- Text is never stored in world state. Events and views carry ids and numbers;
  prose is rendered on the way out.

### 6.3 What `samprocgen` adds to `samtemplate`

`samtemplate` already substitutes variables and lists. `samprocgen` adds what
composition needs: weighted pick-lists; articles and plurals (*an axe*, *two
logs*); list joining (*a, b and c*); conditional clauses; and the name grammars
above.

## 7. An example region

`samworld-lab worldgen --seed 0x1A0 --region 0,0 --dump ascii`

```
      0         1         2         3
      0123456789012345678901234567890123456789
   0  TTTTTTT"""^^^^##^^^""""~~~""""""""""TTTT
   1  TTTTTTTT""^^^####^""""~~~~~"""""""TTTTTT
   2  TTT2TTTT"""^^4##^^"""""~~~~"""""""TTT7TT
   3  TTTTTTT""""""^^^^"""""""~~"""""""""TTTTT
   4  "TT1TT""""""""""""""""""~~""""""""""TTTT
   5  """""""""""H""""""3"""""~"""""""""""""""
   6  """""""""""""""""""""""~~""""^^^^^""""""
   7  ....""""""""""""""""""~~~"""^^^##^^^""""
   8  ......"""""""""TTT""""~~~"""^^#6#^^^""""
   9  ..5....""""""TTTTTT""~~~~~"""^^^^^""""""
  10  .......""""""TT8TTT""~~~~~~"""""""""""..
  11  ........"""""TTTTT"""~~~~~~~"""""""""...

  T ironwood   " meadow   ^ hills   # crag   ~ river   . scrub
```

| Mark | Name            | Kind           | Walk from the landing   | Holds                                |
| ---- | --------------- | -------------- | ----------------------- | ------------------------------------ |
| H    | Ashdown Landing | landing        | —                       | storehouse, kiln, bloomery, the Gate |
| 1    | Hollin Stand    | ironwood stand | 4 min                   | logs                                 |
| 2    | Greywood Deeps  | old stand      | 9 min, through 1        | logs, richer                         |
| 3    | Reedmere Ford   | ford           | 5 min                   | the only crossing                    |
| 4    | Crag's Foot     | outcrop        | 6 min                   | stone                                |
| 5    | Saltscrub Flats | scrub          | 9 min                   | nothing yet                          |
| 6    | Redcap Seam     | iron seam      | 15 min, through 3       | iron ore                             |
| 7    | Far Greywood    | old stand      | 22 min, through 3 and 6 | logs                                 |
| 8    | Southwood       | ironwood stand | 6 min                   | logs; a lair, later                  |

This is an illustration drawn to the rules above, not generator output. The same
dump doubles as the website's map.

## 8. Generation under test

`samworld-lab worldgen --gates --seeds 1000`

| Gate                  | Over a thousand seeds                                                     | Guards against                                   |
| --------------------- | ------------------------------------------------------------------------- | ------------------------------------------------ |
| G1 Connected          | Every site is reachable from every landing                                | Orphaned places                                  |
| G2 Starter guarantee  | Timber within 5 minutes, stone within 8, ore within 15, of every landing  | A House that cannot play                         |
| G3 Fairness           | Within a region, each landing's walk to ore is within ±20 % of the median | Dud starts                                       |
| G4 Variety            | Each site kind's frequency is inside its stated band                      | Tables that do not do what they say              |
| G5 Repairs are rare   | Under 5 % of regions needed a quota repair                                | Leaning on the repairer instead of fixing tables |
| G6 Text               | No duplicate site names in a region; no unfilled slot in any description  | Embarrassing prose                               |
| G7 Order independence | Regions generated in shuffled order have identical hashes                 | Unsafe laziness                                  |
| G8 Golden             | Three blessed seeds: region hashes and ASCII dumps are committed          | Accidental change                                |

Gates G1–G6 are reported as tables with the failing seeds listed, the way
`samgrognard-console gates` links failures to game ids. A failing seed is a
reproducible bug report.

## 9. Growth: the frontier

- A world starts with one shore region generated.
- A new Family takes the next free plot; when a shore fills, the next shore
  region along a fixed spiral opens.
- A wild region is generated the first time anyone's Actor sets out for it.
- Nothing is generated ahead of need, and nothing generated is stored beyond a
  cache.

The world has no edge, only a frontier.

## 10. Generated content beyond the map *(after the slice)*

The same approach makes a deep tech tree wide without typing every row.

| Technique             | From                                                                                                                        | Gives                                                                           |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| **Materials × forms** | 6 materials (tier, hardness, weight) and 12 forms (axe, pick, blade, plate …), each form a formula over material properties | 72 items and 72 recipes from 18 rows, with gates derived from tier × difficulty |
| **Base × affix**      | Monster bases and a few modifiers (*old*, *scarred*, *pack-leading*)                                                        | Many foes with legible differences                                              |
| **A past**            | A short simulation of earlier expeditions run at world creation                                                             | Ruins with reasons, names with history — the Dwarf Fortress move                |

Hand-written exceptions sit on top of the generated bulk. The third row waits
until the world is worth giving a past.

## 11. Where the tables live

| Topic                                       | Source of truth                 | Mirror                        |
| ------------------------------------------- | ------------------------------- | ----------------------------- |
| Biomes, site kinds, node tables, step costs | `libs/samironwood/spec/land.md` | `src/biomes.c`, `src/sites.c` |
| Name grammars and pools                     | `spec/names.md`                 | `src/names.c`                 |
| Description templates and pick-lists        | `spec/text.md`                  | `src/text.c`                  |

The house protocol applies: edit the spec, mirror it, bump the pack version,
rerun the gates, re-bless the goldens.

To add a site kind: one row in the site table, a node table, a name rule and a
description template. No engine change.

**C tables now, data files later.** Tables are compiled in, as in samvillage and
samgrognard, because that needs no parser and every row is type-checked.
Everything enters the engine through the rules builder, so a file loader can be
added later as one more caller. When the text corpus grows large, the
one-concept-per-file layout from `procgen_mvp.md` is the right destination for
it.

## 12. Slice scope

| In the slice                                         | After                                   |
| ---------------------------------------------------- | --------------------------------------- |
| One shore region; passes 0–8 in thin form            | Wild regions and tiers                  |
| Five site kinds: landing, stand, outcrop, seam, ford | Lairs, ruins, more biomes               |
| Site and settler names                               | Cultures                                |
| Glance and look descriptions                         | Examine depth and state-derived clauses |
| Gates G1, G2, G7, G8; the ASCII dump                 | G3–G6; the map on the website           |
| —                                                    | Materials × forms; a generated past     |
