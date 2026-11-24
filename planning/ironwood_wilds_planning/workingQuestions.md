# Ironwood Wilds — Working Questions (review of V1)

*Review of [IronwoodWildsFlavour.md](IronwoodWildsFlavour.md) and [IronwoodWildsMechanics.md](IronwoodWildsMechanics.md), 2026-11-24. Each item has an ID so it can be answered, deferred or struck. Line numbers refer to the docs as they stood at review time. Answered items are deleted once the docs are updated; the decisions are logged in [Mechanics §13](IronwoodWildsMechanics.md#13-open-mechanical-questions).*

---

## A. Bugs & inconsistencies

- [ ] 📌 **A3. Pacing vs. a full reset.** *(Pinned: after the other questions.)* Skills and Tech reset every ~4 weeks, but the skill curve is Lv 1–99 exponential (an MMO-for-years curve). Heart ironwood needs Lv 60; the origin head start is +2–4. Design the curve backwards from the season length (see E2).
- [ ] 📌 **A6. Leftover names from the old brief.** *(Pinned: sweep at the end of the review.)*
  - "retreat to the Keep" ([Mechanics:472](IronwoodWildsMechanics.md#124-hard-rule-goods-and-walls-never-lives)) should be the Hall.
  - **Stone Tools** in Tier I ([Mechanics:295](IronwoodWildsMechanics.md#9-household-tech-tree-changed)) doesn't fit a chartered House in 1300; "charter-issue iron"?
  - **Infirmary** ([Mechanics:156](IronwoodWildsMechanics.md#54-vitals-needs--equipment)) isn't in the tech tree.
  - `03-ProceduralGeneration.md` / `06-VerticalSliceAndRoadmap.md` still say True Ironwood Groves, Barrow, First-Age, Stone keeps.
- [ ] 📌 **A7. Ambiguous units.** *(Pinned: after the other questions.)* "Notice rises per day", "three days at a camp", "Days" in the check-in table: real days or Wilds days? At 1 real day ≈ 1 Wilds week that is a 7× difference. Add a units convention and tag every duration.

## B. Structural gaps (mechanics)

- [ ] **B1. Shared vs. per-House: write the table.** The biggest missing piece.
  - How many Houses per world?
  - One continent and one river graph with Holds along the same coast, or a stretch each?
  - Heart ironwood is "≤2 stands per continent", but delves are instanced and "the same Site claimed by another House is unaffected". Is it actually scarce?
  - First-discovery naming implies a shared map.
  - Deliverable: one table listing every noun as shared or instanced, finite or unlimited.
- [ ] **B2. Production chains.** Several BoM lines have no source.
  - **Cordage / sailcloth:** the Ropewalk exists but there is no fibre crop. Homeland import via the Outfit (makes propulsion hold-limited, an interesting constraint) or a Wilds-native fibre (fen-reed, ironwood bast)?
  - **Pitch / tallow:** presumably ironwood resin and beast fat; state it.
  - Deliverable: a goods catalogue with refine edges.
- [ ] **B3. Beast timelines.** Breeding, egg→imprint and imprint→useful-teg times must fit inside one Chronicle, or breeding isn't a system. Is "plucked each moult" once per Chronicle?
- [ ] **B4. What counts as winning.** What does the Hall of Chronicles rank? Renown earned? The Phase 2 GA work needs a fitness function; players need a scoreboard.
- [ ] **B5. Late joiners & churn.** What does a House that arrives in week 3 of 4 get? What happens to an abandoned House's Yard contributions and claimed Sites?
- [ ] **B6. Generalise the fog rule.** Everything derives from `WorldSeed` streams, and expedition outcomes are decided at departure. Rule: no seed, stream output or precomputed outcome is ever readable through the API (check whether `07-AuthAndSecurityArchitecture.md` already covers it).
- [ ] **B7. Crew cost falls to zero by the Dusk.** Everyone goes home anyway, so late hulls get crewed for free. Fine ("the last galley out, crewed by everyone who could hold an oar")? Decide it on purpose.

## C. Flavour gaps

- [ ] **C1. Faith, oaths & law.** `SUPERSTITIOUS`/`EARNEST` voices mention saints, sin and demons, but the Homeland's beliefs aren't defined. Half a page: sea-saints, house oaths, how debt-bonds work.
- [ ] **C2. Personal & House name grammar, heraldry vocabulary.** Needed by Ticket 14; the register only covers place names.
- [ ] **C3. Journal date format.** How does a settler date an entry? A season-week count ("third week of Thaw") fits.
- [ ] **C4. The First Folk themselves.** The Stewards are made in their likeness, so murals and photos show ordinary-looking people? An unknown script on every sign; scouts copying glyphs could become a curio type.
- [ ] **C5. Recurring NPC voices.** The Yard factor as narrator of Convoy dispatches; named free companies across Chronicles; named Rival hulls in dispatches. Continuity without the Rival ever appearing in person.
- [ ] **C6. State the dramatic irony explicitly.** Modern players will see the Stewards are robots within minutes, and a community wiki will map features to building types within a week. That's the point: the player is in on the joke, the characters aren't. Hidden protects the *text*, not the player's inference. Make salvage value correlate with building type so that decoding the features is a skill.

## D. Opinions on the existing open questions

| Doc Q | Recommendation |
| :--- | :--- |
| Flavour Q1 (bred for this place) | Already resolved as written: truth Hidden, suspicion Rumoured. Keep it. |
| Flavour Q3 (institution names) | Keep *the Council*, *the Arsenal*, *factors*. |
| Flavour Q4 (grim land, lovely beasts) | Yes. It's the tone. |
| Flavour Q5 + Q7 (beasts in the Night; has anyone wintered) | Combine: the beasts winter in the City's warm halls; those who tried to winter followed them for the warmth and were tidied away. Plant one rare Stated trace (a journal page found at Thaw), never explained. |
| Flavour Q6 / Mechanics Q8 (returning hands) | Option (b): same persona, journal and life marks; skills reset. Character attachment is the emotional engine and otherwise resets every 4 weeks. |

## E. Process

- [x] **E1. Tag this as V1.** Done 2026-11-24.
- [ ] **E2. Write a Sample Chronicle per version:** one House's four weeks, check-in by check-in, with rough numbers. The fastest way to find problems like A1–A3, and to check that every check-in actually offers a decision.
- [ ] **E3. Variant versions, one big lever each:** some Tech persists; 2-week vs. 6-week Chronicles; shared vs. instanced groves.
