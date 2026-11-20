# Procedural world — generate places that change decisions

Generate **facts first, prose second**. “The nearest workable seam is two ridges
away” changes how a household values a packing shed. “The ridge is grey” gives
you something to picture. Both belong in the world, but changing the adjective
must never move the seam.

## From a seed to a playable valley

Start with a bounded region containing three named places and the trails between
them. The small grid below is a construction aid; players see a graph with
descriptions, travel times and available work.

| Pass              | How it works                                                                                                                                                                                                                                                                                                                                                                               | What remains                                                                                                                           |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Land           | Sample small integer elevation, wetness and rock fields on a coarse lattice; interpolate with fixed-point arithmetic onto, say, 5 × 5 cells. Derive slope from neighbours. Tag sheltered low ground, woodland and exposed workable rock.                                                                                                                                                   | Terrain tags and positive crossing costs per cell. Nearby cells resemble one another; place types have reasons.                        |
| 2. Landing        | Score walkable cells for shelter, water access and reachable productive neighbours. Select the highest valid score; break ties by `(y,x)`.                                                                                                                                                                                                                                                 | One landing coordinate and a short list of candidate sites.                                                                            |
| 3. Places         | Fill required roles first: a coppice on wooded ground and a seam on exposed rock. Filter content prototypes by tags, then make weighted integer draws from the sorted eligible IDs. Reserve distinct founding cells.                                                                                                                                                                       | Stable place IDs, prototypes, initial quantities and visible clues. Later, optional ruins or monster ranges can fill additional roles. |
| 4. Trails         | Run a least-cost search over walkable neighbours from the landing; prefer stable coordinate order on equal cost. Collapse the two paths into named graph edges. Translate terrain cost to seconds (example: 15 s per cost unit); if two paths share a real junction later, expose it as a place.                                                                                           | A text map with meaningful distances. The same edge costs drive both the map and `travel`.                                             |
| 5. Founding check | Verify both resources are reachable with starter tools, two units fit in a pack, the timber writ can be supplied, and the starter round trips fit the content pack's time budget. Bound search to eight deterministic candidate layouts. If all fail, use a versioned, small fallback valley and record why. Reject an invalid pack rather than hoping the fallback fixes a broken recipe. | A validated blueprint with a digest, generation attempt and any fallback marker. Every household has a way to earn its first recruit.  |
| 6. Describe       | Fill names and short descriptions from terrain, site type, materials and an independent vocabulary stream. `look` gives overview, trails and costs; `examine` gives verified affordances, capacity or signs of recent work as appropriate to the viewer.                                                                                                                                   | Text for a person and structured facts for a bot, from the same place.                                                                 |

For example, the hand-checkable [Alder-01 fixture](FirstExpedition.md) could be
described as: *“Slate Scar rises above the landing. Pale iron shows through a
split in the rock. The downhill trail returns to Landing (45 s). Your claim has
8 workable units; one load uses 2.”* After one load, the remaining amount is 6;
the geology and route are unchanged. A missing tool changes the available-order
reason, not the landscape. The live beginner region reveals all three places;
unexplored later regions need not disclose hidden sites in `/rules` or
descriptions.

## Reproducibility and ownership

- **Identity:** a season pins seed, generator version, rules version, content
  digest and parameter set. A run label such as `Alder-01` is not itself enough
  to replay it. Keep the actual pack and generator version as well as their
  identifiers.
- **Subseeds:** derive independent seeds from
  `(world_seed, generator_version, region_key, layer_tag, stable_feature_key, attempt)`.
  Specify a byte encoding (length-prefixed UTF-8 strings, little-endian
  integers); e.g. use the first eight digest bytes of SHA-256 as a little-endian
  seed for `SamRng`. Use integer draws, sorted candidates and bounded integer
  selection without modulo bias. Pin the algorithm with test vectors. No global
  RNG advanced by discovery or by rendering a page.
- **Stream boundaries:** `terrain`, `places`, `trails`, and `names` do not share
  a draw counter. A cosmetic text edit changes the pack's identity for exact
  textual replay but not rock positions. Runtime chance, when introduced, is
  keyed separately by season and stable event ID. It cannot perturb the map or
  be rerolled by re-reading a report.
- **Base vs history:** materialize the small founding graph at season creation.
  Sites and trails are common facts; competitive depletion of a starter site is
  not. Each household has its own claim quantity there, initially inherited from
  the prototype. Extraction and reservations change that claim only. Buildings,
  footpath improvements, discoveries and shipments are explicit changes, never
  baked back into the seed. A shared milestone can read all completed shipments
  while leaving stores private.
- **Expansion:** if the frontier grows, define region slots and border crossings
  from the common coarse graph first. Detail a region from its key; use one
  canonical ordered pair of region keys for each shared border. Record dynamic
  changes by `(season, region, feature ID)`. Generating east then west must
  produce the same crossings as west then east. This can wait until a single
  valley is fun to watch.

## The author's loop

Keep one starter pack next to the generator, proposed
`libs/samwildsgen/content/frontier-v1/`. Use small JSON definitions for goods,
jobs, sites, offers, buildings, tech nodes and vocabulary; Ptah already has a
cJSON integration. Give definitions stable namespaced IDs. The pack manifest
lists files, schema version and compatible rules version. Both the lab and
server consume the same validated tables. Ptah's `planning/procgen_mvp.md` and
`planning/data_format_plan.md` already describe category slots and text
templates; borrow that authoring idea. The YAML text-generation proposal is a
design reference, not a runtime dependency.

Suppose you want bog iron to be a slow alternative to the scar. One
**illustrative pack record**:

```json
{
  "id": "frontier:bog_iron", "type": "site", "fills": "workable_iron",
  "requires_tags": ["wet", "iron_bearing"], "claim_units": 8,
  "work": {"handler": "gather", "order": "mine", "skill": "mining",
           "rank1_seconds": 210, "extract_units": 2, "good": "frontier:ore", "xp": 1},
  "look": "Rust-coloured water stands in the hollows. The track to {landing} takes {travel_s} s."
}
```

1. Register it as another candidate for the iron role. The basic pick, mining
   queue, ore stack and shipment offers already know what to do with its output.
   A new **kind of consequence**, such as committing a party away from home,
   would merit a C handler instead.
2. Validate the pack once: unique IDs; all references and template fields
   resolve; startable actions have positive durations at every rank;
   costs/yields are bounded; the tech prerequisite graph has no cycles;
   mandatory roles have candidates. Check *reachability from the starter kit* as
   well as graph shape: a pick requiring ore that itself requires that pick is
   an unplayable start even if the tech graph is acyclic.
3. Preview one known seed as a map, an observation and an actual first delivery.
   Then try a fixed panel of seeds: connected starter routes, no overlapping
   founding sites, first-recruit times, spread of route costs and fallback rate.
   Pin a few golden blueprints and make failures print seed, pass and rejected
   condition. Variability is desirable; a frequent fallback is a generator
   defect to investigate.
4. Freeze the validated pack by digest for the new season. Existing live worlds
   keep their definitions, including unfinished job durations. A legacy
   chronicle can still explain why bog iron took 210 seconds.

An author can now release a new valley and ask a concrete question: **does the
wetland variant make timber-first households more common?** Change the relevant
generation parameter or test two policies over matched seeds, keep the other
rules fixed, and use the [lab's measures](FirstExpedition.md). The generator
produces experimental conditions as well as scenery.
