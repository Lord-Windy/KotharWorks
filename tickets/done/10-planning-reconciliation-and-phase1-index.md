# Ticket 10 — Planning Reconciliation & Phase 1 Ticket Index

- **`st` Key:** `0fac031c` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Ready
- **Depends on:** Phase 0 (Tickets `01`–`09`, all done)
- **Modules touched:** `planning/ironwood_wilds_planning/*.md`, `tickets/README.md`, `tickets/done/` *(docs only — zero Java/SQL changes)*

---

## 1. Objective

Phase 0 ("The Bare Engine Spine") deliberately diverged from the original planning suite: it removed `libs/procgen`, used neutral `Owner`/`Actor`/`Place` primitives instead of `Household`/`Character`/`Site`, and built the server/persistence milestone (M4) before the gameplay milestones (M1–M3). The planning suite still describes the *original* plan and is labelled **normative** — so every Phase 1 agent reading it will be steered wrong.

This ticket makes the planning suite and the ticket index tell the truth about what exists today and record the two structural decisions Phase 1 builds on, **before** any gameplay ticket starts:

1. **Neutral engine IDs stay.** `OwnerId`/`ActorId`/`PlaceId` are permanent (they are baked into the WAL, the wire format, `EventPayloadCodec` and SQL). Gameplay concepts are layered on as tables, rule families and DTO fields keyed by those IDs — never by renaming them.
2. **`libs/procgen` returns when the first real generator lands** (planned: Phase 1 personas — `NameGrammar` + `PersonaGenerator`), absorbing `WorldSeed`/`SeededRng` from `com.kotharworks.wilds.seed`. Until then, the `com.kotharworks.wilds.worldgen` seams remain the only procgen surface.

---

## 2. Explicit Non-Goals

- Zero code, SQL, or test changes. If a doc fix reveals a code bug, file a separate ticket.
- Do **not** rewrite the gameplay design (`IronwoodWildsGameplay.md`) or the long-form architecture prose beyond what is needed to remove false statements. A short mapping note at the top of a doc is preferred over a line-by-line rewrite.
- Do **not** cut `st` tickets for Phase 1 beyond `10` and `11` — the index lists the planned tickets `12`–`24` as *planned* (no keys).

---

## 3. Deliverables

### 3.1 Canonical Noun Mapping (new section in `planning/ironwood_wilds_planning/README.md`)

Add a **"Engine Primitives vs. Gameplay Nouns"** table and reference it from AR-1 and from the top of `01`, `02`, `04`, `06`, `07`:

| Gameplay noun (design docs) | Engine primitive (code) | Notes |
| :--- | :--- | :--- |
| Household / House | `Owner` (`OwnerId`, `OwnerRow`) | One permanent seat (`wilds_meta.seats`) charters exactly one Owner per world. |
| Settler / Character | `Actor` (`ActorId`, `ActorRow`) | Generational ID; a dead settler's slot generation is retired, stale IDs reject. |
| Site / Waypoint / Region node | `Place` (`PlaceId`, `PlaceRow`, `PlaceEdge`) | Travel graph edges carry `travelDurationMs`. |
| Hold, Party, Stockpile, Tech unlocks | *(new tables keyed by `OwnerId` / `ActorId`)* | Added in Phase 1 tickets; never as object references. |
| Item / Skill / Tech / Building kinds | `RuleRegistry` families keyed by `RuleId` | Added in Phase 1; versioned by `WILDS_RULES_VERSION` (Ticket `11`). |
| `CharacterId`, `HouseholdId`, `SiteId`, `HoldId` | — | **Historical names only.** Do not introduce these types. |

### 3.2 `planning/ironwood_wilds_planning/README.md` Corrections

- Module tree (§2): show the **real** layout — `libs/wilds`, `libs/auth`, `apps/wilds-headless`, `apps/wilds-server`, `systest/` (opt-in `-Psystest`) — with `libs/procgen`, `apps/wilds-bot`, `libs/samsim`, `libs/samvm`, `libs/samgenai`, `apps/wilds-arena` marked *planned* and the phase/ticket that introduces each.
- Replace the "Module Discipline" note ("start directly inside the existing `libs/procgen`…") with decision 2 from §1 above.
- Add a short **"Current Status"** section near the top: Phase 0 complete (link `tickets/done/`), Phase 1 in progress (link `tickets/README.md`), and the one-command gate `./systest.sh`.
- AR-1: refer to `ActorId`/`OwnerId`/`PlaceId` (generational `index, generation`) and link the mapping table.
- AU-1 and any other "12 `AuthSecurityGateTest`" references: the suite has **16** tests today; state the real number (or "the `AuthSecurityGateTest` suite") so it does not rot again.
- Architecture ASCII box for `libs/procgen`: label it *planned (Phase 1)*; note that seed primitives currently live in `libs/wilds` (`com.kotharworks.wilds.seed`).
- Document Guide entry 6/7 and the ASCII topology: mention `systest/` and `./systest.sh` as the definition-of-done gate.

### 3.3 `06-VerticalSliceAndRoadmap.md` Corrections

- §3 Package Skeleton: replace the aspirational tree with the **actual** packages (`id`, `sim`, `state`, `rules`, `command`, `task`, `event`, `observe`, `replay`, `seed`, `worldgen` in `libs/wilds`; real `apps/wilds-server` packages `domain`/`ports`/`adapters/{http,memory,postgres,web}`), then a clearly separated *"Planned Phase 1 additions"* list (e.g. `libs/procgen` `persona/`, `prose/`, `geography/`, `delves/`; `libs/wilds` `economy/`, `combat/`).
- §4.1 Milestone table: add a **Status** column. Mark M0 done; M4a done; M4b partially done (single-actor `/play` console, no multi-tab deck/persona/journal yet); M4c partially done (Postgres + crash recovery done; Keycloak `deploy/` compose and live OIDC path not done); M1–M3, M5 not started. Note that execution order was M0 → M4 → (M1–M3).
- §4.2 Ticket backlog: replace the original 19-item list with a pointer to `tickets/README.md` plus a compact mapping of Phase 0 tickets `01`–`09` and planned Phase 1 tickets `10`–`24` to milestone IDs (table below in §3.4).
- §4.3 Review checklist item 6: reference Ticket `11`'s mechanical rules-version pin once it lands ("…bumps `WILDS_RULES_VERSION`; `RulesVersionPinTest` enforces this").

### 3.4 `tickets/README.md` → Phase 1 Index

1. Move the current Phase 0 index verbatim to `tickets/done/PHASE0-README.md` (fix its relative links to `done/` files so they still resolve).
2. Rewrite `tickets/README.md` as the **Phase 1 — Slice 0 Gameplay ("Hearth, Iron, and the Palisade")** index:
   - **Guiding philosophy:** gameplay now lands *on top of* the neutral spine (mapping table from §3.1); every gameplay ticket is a vertical brick (engine → wire/`/api/v1/rules` → `/play` where user-visible → live `systest`).
   - **Build order & dependency graph** and the planned ticket table:

     | # | Ticket | Depends on | Milestone |
     | :---: | :--- | :---: | :---: |
     | 10 | Planning reconciliation & Phase 1 index *(this ticket)* | — | — |
     | 11 | Rules-version gate: `RulesFingerprint` pin, clean boot refusal & world rollover | 10 | M2 prep |
     | 12 | Items, stockpiles, carry & `Deposit` | 11 | M1b |
     | 13 | Resource nodes, `Gather` ×N & fail-soft `DEPLETED` | 12 | M1b |
     | 14 | Restore `libs/procgen`: `NameGrammar`, `PersonaGenerator`, 3-settler charter | 10 | M1b |
     | 15 | Bounded 3×3 region geography, `WaypointGraph` & `headless gallery` | 14 | M1a |
     | 16 | `ProseComposer` (`glance`/`look`/`examine`) & `JournalComposer` | 13, 14 | M1b |
     | 17 | `/play` multi-tab Character Deck (persona, journal, stockpile, queue builder) | 13, 16 | M4b |
     | 18 | Skills, XP curve & stamina modifiers | 13, 14 | M2 |
     | 19 | Craft, Research & Construct (4 techs, 5 buildings) | 18 | M2 |
     | 20 | Tithe Ship heartbeat, Favor & `RecruitSettler` | 19 | M2 gate |
     | 21 | Hold rings, raid rumor & non-lethal `RaidResolver` | 19 | M3 |
     | 22 | Parties, `LairGraphGenerator`, lazy muster & `CombatDoctrine` | 15, 18 | M3 |
     | 23 | `ExpeditionResolver`, permadeath, scars & Memorial Cairn | 22 | M3 gate |
     | 24 | `apps/wilds-bot` `GreedyFrontierDoctrine` & 3-day soak | 20, 21 | M5a gate |
     | infra | Keycloak `deploy/` compose + live OIDC `systest` path | — | M4c |

     Only `10` and `11` carry `st` keys at this point; the rest are marked *planned*.
   - **Ticket spec template** for tickets 12+: header (`st` key, status, depends on, modules touched), `1. Objective`, `2. Explicit Non-Goals`, `3. Deliverables`, `4. Tests & Acceptance Criteria`, `5. Review Focus` (rules in `AGENTS.md` → *Ticket specs & Review Focus*).
   - **Testing pyramid** additions for Phase 1: story tests (headless scripted scenarios), conservation/invariant fuzz, golden `WorldHash` + rules-version pin, live bot/human `systest`.
   - **New Definition of Done** replacing Phase 0's. It must **drop** the "zero gameplay-specific nouns" rule (every Phase 1 ticket would violate it) and replace it with:
     - [ ] `mvnd test` passes with zero warnings; `./systest.sh` passes.
     - [ ] Apache 2.0 headers on new `.java`/`pom.xml`; `PROJECTS.md` updated for module/feature changes.
     - [ ] Engine IDs stay neutral (`OwnerId`/`ActorId`/`PlaceId`); gameplay nouns are allowed in rule families, state tables keyed by those IDs, events, DTOs, UI and SQL.
     - [ ] Any rule/mechanic change re-blesses the golden hash and bumps `WILDS_RULES_VERSION` (enforced by Ticket `11` once landed).
     - [ ] New gameplay/API behaviour has a live test in the matching `systest/` package; user-visible features have a human-mode test; bug fixes have a regression test.
     - [ ] Client responses go only through `ObservationProjector` DTOs (no `*Row` serialization); purity/no-float gates stay green.
     - [ ] The spec has a `Review Focus` section, and a deep review (`prompts/deep-review.md`) has run before any dependent ticket starts; confirmed `[HIGH]` findings are fixed.
     - [ ] Completed ticket moved to `tickets/done/`, committed, pushed, `st status <KEY> done`.

### 3.5 Lighter-Touch Fixes in `01`, `02`, `04`, `05`, `07`

- Add a one-paragraph **"Phase 0 reality note"** at the top of each that links the mapping table and states which parts are implemented (with the owning `tickets/done/` file) vs. planned. Do not rewrite code samples that use historical names; the note covers them.
- `07`: fix the `AuthSecurityGateTest` count (see §3.2).
- `05`: confirm the DDL shown matches `apps/wilds-server/src/main/resources/db/V00{1,2,3}__*.sql`; where it does not, state that the migration files are authoritative and list the material differences (e.g. seat-centric `wilds_meta` instead of `UserId`-owned Houses).

### 3.6 Diagrams (best effort)

- If `dot` is available, update `diagrams/01-system-architecture.dot` (procgen planned, add `systest`) and `diagrams/07-milestone-roadmap.dot` (status colouring) and regenerate `.svg`/`.png`.
- If not, add a caption under each embedded diagram: *"Diagram shows the target architecture; see Current Status for what exists today."* Do not hand-edit SVGs.

---

## 4. Acceptance Criteria

- `grep -rn "existing \`libs/procgen\`" planning/` returns nothing.
- Every occurrence of `CharacterId`, `HouseholdId`, `HoldId`, `SiteId` in `planning/ironwood_wilds_planning/*.md` is either replaced or sits in a doc that opens with the reality note linking the mapping table.
- No doc in the suite states a wrong `AuthSecurityGateTest` count.
- `tickets/README.md` is the Phase 1 index with the new Definition of Done; `tickets/done/PHASE0-README.md` exists and its links resolve.
- All relative Markdown links in `planning/ironwood_wilds_planning/` and `tickets/` resolve to existing files (check with a quick script or `grep -o '](...)'` sweep; include the command used in the commit message).
- `mvnd test` still passes (sanity only — no code changed). `./systest.sh` is not required for a docs-only change, but must not be broken by it.
- Ticket file moved to `tickets/done/`, committed, `st status 0fac031c done`.

---

## 5. Review Focus

Where this ticket is most likely to be wrong. The reviewer gives each item a verdict (bug / proven safe / undetermined) with evidence (see `prompts/deep-review.md`).

1. **New false statements.** For every claim the updated docs make about *current* code (packages, flags, test counts, schema, milestone status), spot-check it against the source. A reconciliation ticket that introduces new drift is worse than none.
2. **Definition of Done replaced, not appended.** The Phase 0 "zero gameplay-specific nouns" rule must be gone from the *active* `tickets/README.md` (it may remain in `tickets/done/PHASE0-README.md`). Check nothing else in `AGENTS.md` or the planning suite still imposes it.
3. **Decisions recorded consistently.** The neutral-ID decision and the `libs/procgen` return decision should read the same in `README.md`, `06`, and `tickets/README.md`. Look for one doc still saying `CharacterId` is the target type, or that procgen lives in `libs/wilds` permanently.
4. **Broken links.** Moving the Phase 0 index into `tickets/done/` changes every relative link in it. Run the link sweep and include its output.
5. **Scope creep.** No Java/SQL/test changes, and no rewrite of the gameplay design. Check `git diff --stat` touches only Markdown (and diagram files if `dot` was used).
6. **Ticket template present.** The Phase 1 index includes the spec template with a `Review Focus` section (see §3.4), so tickets 12+ are cut with one.
