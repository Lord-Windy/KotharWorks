# 03 — How We Do Procedural Generation Procedurally (`libs/samprocgen`)

In a text-based simulation like **Ironwood Wilds**, "procedural generation" actually means **two distinct engines working in tandem**:

1. **Spatial & Structural Generation:** Generating coherent geography, resource geology, frontier Hold clearings, and multi-room monster lairs/ruins from a 64-bit `world_seed`.
2. **Hierarchical Text Composition:** Translating any region, Hold, room, character, or item into layered, Dwarf Fortress-style prose (`look` → `examine` → `inspect detail`) where **every adjective reflects real simulation state**, never disposable flavor lies.

Notice that you already designed the foundation of both in Ptah (`planning/samsimulator.md`'s seeded `f(x, y)` fields and `planning/procgen_mvp.md`'s YAML category/slot/table text engine + `libs/samstring`'s `SamTemplate`). Here is how we unify them into `libs/samprocgen` and `libs/samwilds`.

---

## 1. Golden Rule: Coordinate Hashing, Not Sequential RNG

Never generate an online world by pulling numbers sequentially from one global `SamRng` as players explore — otherwise, whether Player A walks North before Player B walks East changes what exists in the East!

Instead, every spatial layer uses **pure, order-independent coordinate hashing** (SplitMix64 / Murmur3 via `samdata`'s `SamHash`):

```c
static inline uint64_t wilds_coord_seed(uint64_t world_seed, uint32_t layer_salt,
                                        int32_t x, int32_t y) {
  uint64_t h = world_seed ^ ((uint64_t)layer_salt * 0x9E3779B97F4A7C15ULL);
  h ^= (uint64_t)(uint32_t)x * 0xBF58476D1CE4E5B9ULL;
  h = (h ^ (h >> 27)) * 0x94D049BB133111EBULL;
  h ^= (uint64_t)(uint32_t)y * 0xD6E8FEB86659FD93ULL;
  h ^= (h >> 31);
  return h;
}
```
Feed `wilds_coord_seed(...)` into a local stack-allocated `SamRng` whenever you evaluate `(x, y)`. Result:
- **Infinite / Huge Worlds at 0 RAM Cost:** Unvisited regions take **0 bytes** in RAM and **0 rows** in PostgreSQL.
- **Deterministic Reproducibility:** Given `world_seed = 0x49524F4E`, tile `(-14, 22)` produces the exact same Slate Crag, Tin Seam, and Sunken Watchtower on every machine forever.

---

## 2. Layer-by-Layer World Generation Pipeline

We model the world as a grid of **Regions** (each ~1 league across, containing 3–8 discoverable **Sites**). Regions generate through five composable mathematical passes:

```
  world_seed + (rx, ry)
        │
        ▼
 [Pass 1: Continuous Fields]  elevation · moisture · geology · wild_depth
        │
        ▼
 [Pass 2: Biome & Ecology]    Salt Marsh · Bog Fen · Pine Barrens · Slate Crags · Ironwood Deep
        │
        ▼
 [Pass 3: Charter Ring Mask]  Safe coastal/river landing ring for Household Holds; danger scales inward
        │
        ▼
 [Pass 4: Jittered POI Sites] Timber stands · Ore seams · Quarries · Ruins · Beast Lairs
        │
        ▼
 [Pass 5: Site Internals]     Depletable yield pools + procedural room-graphs for Barrows/Ruins
```

### Pass 1: Continuous Geophysical Fields `f(rx, ry) -> [0..1000]`
Using integer/fixed-point 2D Simplex/Value noise (3 octaves so C and WASM/test platforms match bit-for-bit without libm float drift):
- **`elevation(x, y)`** — Low fens and river valleys vs. windswept crags.
- **`drainage(x, y)`** — Poor drainage + low elevation = **Peat & Bog-Iron Fens**; high drainage + crags = **Dry Quarries & Deep Veins**.
- **`geology(x, y)`** — Underlying rock province (Limestone/Flint, Slate/Copper, Granite/Iron). Ensures realistic regional specialization: a Household settled near fens swims in bog-iron and peat but must trek to the crags for good masonry stone!
- **`wild_depth(x, y)`** — Radial distance from the Charter Landing Coast + low-frequency noise. Controls ancient forest density, monster tier, and relic richness.

### Pass 2: Biome Derivation Table (Data-Driven)
Biomes are selected by matching the field tuple against a rule table rather than hardcoded `if/else` spaghetti:

| Biome | Field Predicate | Native Timber / Stone | Native Ores & Herbs | Typical PvM Threats |
|---|---|---|---|---|
| **Charter Shingle & Lea** | `depth < 150` | Driftwood, Young Pine, Fieldstone | Surface Flint, Reed-Flax | Stray Boars, Cutpurses (Tier 1) |
| **Mist-Choked Bog Fen** | `elev < 300 && drain < 350` | Alder, Bog-Oak, Peat | **Bog-Iron Pans**, Feverfew | Fen-Hounds, Mire Lurkers (Tier 1–2) |
| **Whispering Pine Barrens** | `elev 300..650 && drain >= 350` | Tall Pine, Ash, Sandstone | Resin, Game Trails | Wolf Packs, Outlaw Camps (Tier 2) |
| **Riven Slate Crags** | `elev > 650` | Scrub Birch, **Slate & Granite** | **Copper, Tin, Vein Iron** | Crag-Cats, Brigand Towers (Tier 2–3) |
| **The Deep Ironwood** | `depth > 600 && moisture > 500` | **Ironwood**, Ancient Yew | Star-Metal, Blood-Moss | Barrow-Wights, Iron-Tuskers (Tier 3–4) |

### Pass 3: Fair Household Settlement Placement ("The Charter Ring")
When a new player or bot creates a Household, where does their Hold spawn?
- We place new Holds along the **Charter Ring** (`wild_depth` band `80..180`), spaced via a **jittered grid cell** (1 Hold slot per starter valley, minimum distance `D` apart) where a starter readiness check guarantees within distance 1:
  - ≥ 1 starter Wood source, ≥ 1 starter Stone/Clay source, ≥ 1 low-tier Bog-Iron/Copper source.
- As more players/bots join (whether 10 or 500), the frontier arc naturally expands along the coast/river mouth without cramming 50 lumberjacks onto the same starter tree, while everyone faces the **same inward push** toward contested high-tier Ironwood regions!

### Pass 4 & 5: Sites & Procedural Ruin/Lair Graphs
Inside region `(rx, ry)`, `coord_seed(seed, SALT_SITES, rx, ry)` rolls `N` Sites (some immediately visible on entering the region; hidden veins and barrows revealed only by running `SURVEY` checks weighted by `Wayfaring` skill!).
- For **PvM Ruins / Barrows**, Pass 5 builds a tiny **3-to-7 node DAG (Directed Acyclic Graph)** of chambers:
  - `[Threshold] → [Guardroom / Outer Den] → (branch) → [Collapsed Vault] / [Barrow Sanctum]`.
  - Each node slots in procedural encounters, salvage caches, and environmental hazards (e.g., *Flooded Passage: heavy armor −15% initiative*).

---

## 3. Procedural Text Generation (`libs/samprocgen`)

Directly evolving your `planning/procgen_mvp.md` specification: structure and prose live in YAML/compiled tables joined by **categories**, rendered through `libs/samstring`'s `SamTemplate` — with one vital upgrade for a simulation game: **State-Bound Predicates**.

### 3.1 The Truthfulness Principle (No "Fake Flavor")
In pure toy procgen, a room randomly says *"iron ore glints in the wall"* even after a player mined it dry. In **Ironwood Wilds**, every entity exposes a small bag of **live simulation tags & ratios** to `samprocgen`:
- `yield_pct` (`100%` = untouched, `20%` = nearly exhausted, `0%` = stripped bare)
- `condition_pct` (`100%` = newly raised, `45%` = weathered, `15%` = raid-scarred/splintered)
- `weather` (`clear`, `mist`, `driving_sleet`)
- `craft_tier` (`crude`, `journeyman`, `masterwork` from creator's skill level)

### 3.2 Hierarchical Drill-Down: 3 Zoom Levels
Just like `procgen_mvp.md` §2.2, players and curious onlookers can inspect at three nested depths without flooding the main feed:

1. **Level 1 — Overview (`look` at Region / Hold):** Scannable 2–3 sentences + structured site/occupant list.
2. **Level 2 — Site / Room / Building (`look site-9` or `look bld-gate`):** Composes structural slots, active workers, and resource abundance cues.
3. **Level 3 — Object / Character Inspection (`examine item-44` or `examine chr-4`):** Recursive detail slots — material, maker's mark, wear surface, combat scars, recent raid scorch marks.

### 3.3 Concrete Data & Rendering Walkthrough

```yaml
# data/sites/bog_iron_fen.yml
type: site
categories: [extraction_site, wetland_site]
singular: bog-iron pan
plural: bog-iron pans
slots:
  - category: fen_flora
    min: 1
    max: 2
  - category: ore_sign
    min: 1
    max: 1
description:
  brief: "A {adjective} bog-iron pan ({abundance_tag})."
  look: >
    Rust-red water pools between tussocks of {fen_flora_list}.
    {ore_sign_desc} {activity_clause}
tables:
  adjective: [sunken, reed-choked, mist-hung, black-water]
state_bindings:
  abundance_tag:
    - when: "yield_pct >= 70"
      pick: ["rich rust-crust", "thick ochre seams"]
    - when: "yield_pct >= 1"
      pick: ["partially dug-over", "muddy andPicked thin"]
    - when: "yield_pct == 0"
      pick: ["dug out and flooded", "barren mud"]
```

```yaml
# data/items/spear.yml
type: item
categories: [weapon, polearm]
singular: "{metal} spear"
plural: "{metal} spears"
slots:
  - category: haft_detail
    min: 1
    max: 1
  - category: makers_mark
    min: 0
    max: 1
    require_tag: "craft_tier == masterwork"
wear:
  surfaces: [ash haft, socketed point]
  levels:
    - when: "condition_pct >= 80"
      text: "The {surfaces} are oiled and keen."
    - when: "condition_pct >= 35"
      text: "Nicks mar the {surfaces} from field use."
    - when: "condition_pct < 35"
      text: "The {surfaces} are notched and badly sprung."
description:
  examine: "A {craft_adj} {metal} spear. {haft_detail_desc} {wear_text} {makers_mark_desc}"
```

#### Resulting Seeded Output (`look site-12` at `yield_pct = 85%`, 1 miner active)
> *"Rust-red water pools between tussocks of wire-sedge and bog-myrtle. Thick crusts of orange bog-ore cling to the submerged peat banks. Aldric of House Ashvale works knee-deep in the mire with an iron mattock."*

#### Resulting Output 20 Minutes Later (`yield_pct = 0%`)
> *"Rust-red water pools between tussocks of wire-sedge and bog-myrtle. Flooded shovel-pits yawn where the ochre crust has been stripped clean."*

Same seed, same reeds, **live simulation truth** — zero LLM latency, microseconds of C string-builder execution inside `conn_arena`!
