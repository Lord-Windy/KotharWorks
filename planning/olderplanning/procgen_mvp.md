# ProcGen MVP — Procedural Text Generation

## 1. Overview

### 1.1 Purpose

A procedural generation library that outputs text descriptions of environments
and objects, rather than visual art or images. The system generates detailed,
explorable textual worlds — rooms, buildings, and the items within them — with
layered levels of detail.

### 1.2 Analogy

Think Dwarf Fortress for text: a vast repository of things, combined with
procedural techniques to compose rich, unique descriptions. No AI involved.

### 1.3 Target Use Case

Text adventures, MUDs, or any system where a player or reader "looks" at
something and receives a procedurally generated textual description. High-level
views (e.g., "a bedroom with a bed, two chairs, and a wardrobe") drill down
into detail (e.g., examining the bedspread reveals a floral pattern; looking at
the carpet reveals cigarette stains).

## 2. Core Concepts

### 2.1 Two-Part Generation

1. **Environment generation** — creating rooms, buildings, factories, houses,
   and populating them with appropriate items.
2. **Textual output generation** — producing descriptions at varying levels of
   detail, from a broad room overview down to individual object examination.

### 2.2 Hierarchical Detail

- **Room level**: What's in the room (e.g., bed, chairs, wardrobe).
- **Item level**: What an item looks like (e.g., the bed has one pillow in the
  middle, suggesting the sleeper sleeps centrally).
- **Detail level**: Fine-grained examination (e.g., the bedspread has a floral
  design; the carpet has cigarette stains).

### 2.3 Detail Sources

Inspired by Dwarf Fortress: detail is drawn from multiple layers — material
properties, wear patterns, usage indicators — and composed into coherent
descriptions.

**MVP scope:** materials, conditions, wear, and compositional variation.
**Post-MVP:** historical events and event-based detail (e.g., "this chair was
used by the previous owner").

## 3. Data Layer

### 3.1 Repository of Things

A structured inventory of everything that might exist in a generated world
(e.g., furniture types, clothing articles, materials, decorations). Items are
stored as data, not hardcoded descriptions.

### 3.2 Relational Requirements

The system is inherently relational. For example:
- A room type defines what furniture is normal for it.
- Furniture defines what details and sub-items it can have.
- Materials and conditions apply across item types.

### 3.3 Storage Format — YAML Files with Category Index

**Decision: YAML files, one concept per file, joined by categories.**

Each entity (room, item, detail) is a single YAML file. Entities declare what
categories they belong to or what categories they need (via slots). Categories
are the join key — rooms don't reference specific items, they reference
categories like `sleeping_furniture`; items don't reference specific details,
they reference categories like `bedspread`.

#### Directory Structure

```
data/
  categories.yml        # central index of all categories per type
  tables/                # shared global pick-lists
    material.yml
    pattern.yml
    adjective.yml
    size.yml
    direction.yml
  rooms/
    bedroom.yml
    kitchen.yml
  items/
    bed.yml
    painting.yml
  details/
    bedspread.yml
    picture-subject.yml
    weave-pattern.yml
  templates/
    room.yml
    item.yml
    detail.yml
```

#### Central Category Index (`categories.yml`)

A single file listing every category for each type. This allows the loader to
build lookup tables in one pass rather than scanning every file.

```yaml
types:
  rooms:
    - sleeping_quarters
    - cooking_area
    - storage_room
  items:
    - sleeping_furniture
    - decoration
    - storage
    - seating
  details:
    - bedspread
    - pillow
    - painting_subject
    - material
    - pattern
```

#### File Format — Templates + Slots + Tables

Structure and content are separated. Each entity defines **slots** (what it
needs filled) and **tables** (pick-lists for procedural selection).

```yaml
# data/rooms/bedroom.yml
type: room
name: bedroom
singular: bedroom
plural: bedrooms
slots:
  - category: sleeping_furniture
    min: 1
    max: 1
  - category: decoration
    min: 1
    max: 3
  - category: storage
    min: 0
    max: 2
description:
  enter: "You step into a {adjective} bedroom."
  look: "A {sleeping_furniture} dominates the room. {decoration_list}."
tables:
  adjective: [spacious, cramped, dimly-lit, well-furnished]
```

```yaml
# data/items/bed.yml
type: item
categories: [sleeping_furniture]
singular: bed
plural: beds
slots:
  - category: bedspread
    min: 1
    max: 1
  - category: pillow
    min: 1
    max: 4
description:
  look: "A {size} bed against the {direction} wall."
  examine: "The bed is made up with a {bedspread}. {pillow_desc}"
tables:
  size: [single, double, king-size]
  direction: [north, south, east, west]
```

```yaml
# data/details/bedspread.yml
type: detail
categories: [bedspread]
description:
  examine: "A {material} bedspread patterned with {pattern}."
tables:
  material: [cotton, silk, quilted, threadbare]
  pattern: [floral vines, faded roses, geometric shapes, hunting scenes]
use-global-lists: true
```

#### Global Tables

Shared pick-lists live in `data/tables/`. Each file defines a single table that
any entity can reference by name. This avoids duplicating common tables (e.g.,
`material`, `pattern`, `adjective`) across many files.

```yaml
# data/tables/adjective.yml
name: adjective
entries: [spacious, cramped, dimly-lit, well-furnished, bare, cluttered]
```

```yaml
# data/tables/material.yml
name: material
entries: [oak, pine, mahogany, iron, brass, cotton, silk, wool, leather]
```

Entities reference global tables by name in their `tables` block. The
`use-global-lists` flag controls merge behaviour:

| `use-global-lists` | Behaviour |
|---|---|
| `true` (default) | Entity-local entries are **added** to the global table entries |
| `false` | Entity-local entries **replace** the global table entirely |

#### Detail Nesting

Details may contain their own `slots`, allowing recursive detail composition.
The hierarchy is: room → item → detail → detail → ... There is no fixed depth
limit; generation stops when a detail has no further slots or when a slot is
not filled.

```yaml
# data/details/material.yml
type: detail
categories: [material]
slots:
  - category: wear_pattern
    min: 0
    max: 1
description:
  examine: "Made of {material_choice}. {wear_pattern}"
tables:
  material_choice: [oak, pine, mahogany, iron, brass]
```

#### Composition Placeholders

Templates use two conventions for aggregated slot output:

| Placeholder | Output |
|---|---|
| `{category_list}` | Enumerated list (e.g., "a painting, two chairs, and a wardrobe") |
| `{category_desc}` | Joined descriptions from each resolved item (e.g., "The oak chairs are worn at the edges. The mahogany wardrobe stands tall.") |

The `_list` suffix produces a compact enumeration with correct articles and
conjunctions. The `_desc` suffix concatenates the resolved description of each
item in the slot.

#### Pluralization

All entities that can appear in quantities carry `singular` and `plural` name
fields. The text generation layer uses these to produce grammatically correct
output (e.g., "1 pillow" vs "3 pillows", "a chair" vs "two chairs").

```yaml
# data/items/chair.yml
type: item
categories: [seating]
singular: chair
plural: chairs
```

Irregular forms are handled naturally since both forms are stored explicitly.

#### Wear

Items that can show wear declare a `wear` block in their schema. Wear is
applied during generation and feeds into descriptions.

```yaml
# data/items/chair.yml
type: item
categories: [seating]
singular: chair
plural: chairs
wear:
  surfaces: [legs, seat, armrests]
  levels:
    - condition: pristine
      description: "The {surfaces} are unmarked."
    - condition: worn
      description: "The {surfaces} show signs of daily use."
    - condition: damaged
      description: "The {surfaces} are cracked and splintered."
```

Wear level is selected during generation. History (event-based detail such as
"this chair was used by the previous owner") is deferred to post-MVP.

#### Why This Works

| Concern | How it's handled |
|---|---|
| **Relationships** | Slots with `category` matching — items declare categories they fill, rooms declare categories they need |
| **Nesting** | Details can have their own slots, enabling recursive detail (room → item → detail → detail → ...) |
| **Not too granular** | Templates compose at any depth. Stop wherever you want. No bedspread detail? Skip that slot |
| **Procedural** | `tables` are pick-lists. Walk the slot tree, pick items, resolve templates |
| **Global tables** | Shared pick-lists in `data/tables/`, referenced by name. `use-global-lists` controls extend vs replace |
| **Pluralization** | Entities carry `singular`/`plural` fields; text layer uses correct form based on count |
| **Wear** | Items declare `wear` block with surfaces and levels; selected during generation |
| **Git-friendly** | One concept per file, YAML diffs cleanly, merging is straightforward |
| **Fast loading** | `categories.yml` lets the loader build category → file mappings in one pass |

## 4. Technology Stack

### 4.1 Language

Written in C.

### 4.2 Existing Libraries (in this repo)

- **samrena** — Memory arena management. All data structures will be
  arena-backed.
- **samdata** — Data structures (vectors, hash maps, sets). Used for collection
  types throughout.

### 4.3 External Dependencies

- **libyaml** — YAML parser and emitter library. Used to load the data files
  described in section 3.3.

### 4.4 String Handling Library — Needed

C's native string handling is inadequate for this project. A new string
handling library is needed, in the same vein as samrena and samdata.
Requirements:

- Comfortable string building, formatting, and manipulation.
- Arena-compatible (allocates from samrena arenas).
- Template support (see 5.1).

This is a prerequisite for the text generation layer.

## 5. Text Generation

### 5.1 String Templates

A template system for composing descriptions. Templates allow combining fixed
text with dynamic data (e.g., "A {material} {item} with {pattern} lies on the
{surface}.").

### 5.2 Procedural Text Techniques — Research Needed

Techniques for procedurally varying and composing text, such as:

- Template selection and variation.
- Detail injection based on object properties.
- Compositional rules (how to combine multiple details into a coherent
  paragraph).

This is an open research area. Approaches will be evaluated during prototyping.

## 6. Room Generation

### 6.1 Room Composition

Rooms are generated by:
1. Selecting a room type (bedroom, kitchen, factory floor, etc.).
2. Populating it with appropriate items based on relational data.
3. Applying variation and detail to each item.

### 6.2 Detail Accumulation

Items accumulate detail over the generation process — materials, conditions,
patterns, usage signs, etc. This detail is what feeds the text generation
layer.

## 7. Dependencies and Build Order

### 7.1 Phase 1 — Prerequisites

1. ~~Research and decide on data storage format (3.3).~~ Done.
2. Build string handling library (4.4).

### 7.2 Phase 2 — Data Foundation

1. Define the item/furniture/material schema.
2. Populate initial repository of things.
3. Build data loading layer.

### 7.3 Phase 3 — Generation

1. Implement room generation logic.
2. Implement text template system.
3. Implement procedural text composition.
4. Wire together: data → generation → text output.

### 7.4 Phase 4 — MVP Demo

1. Generate a sample environment (e.g., a house with several rooms).
2. Demonstrate hierarchical detail (room → item → detail).
3. Output readable, varied text descriptions.
