# KotharWorks — Phase 1 Slice 0 Gameplay Tickets ("Hearth, Iron, and the Palisade")

This folder tracks the Phase 1 ticket set that layers the first playable **Ironwood Wilds** gameplay slice on top of the neutral engine and server spine built in Phase 0.

When a ticket is implemented, verified and committed, move its markdown file from `tickets/` into `tickets/done/`. The Phase 0 index is archived at [`done/PHASE0-README.md`](done/PHASE0-README.md). The planning suite lives at [`../planning/ironwood_wilds_planning/README.md`](../planning/ironwood_wilds_planning/README.md).

---

## 1. Guiding Philosophy

Gameplay now lands *on top of* the neutral spine (mapping table: [Engine Primitives vs. Gameplay Nouns](../planning/ironwood_wilds_planning/README.md#engine-primitives-vs-gameplay-nouns)). Every gameplay ticket is a vertical brick: engine → wire / `/api/v1/rules` → `/play` where user-visible → live `systest`.

Two decisions are fixed for the whole phase:

- **D1:** Neutral engine IDs are permanent. `OwnerId`, `ActorId` and `PlaceId` (generational `(index, generation)` records in `com.kotharworks.wilds.id`) are baked into the WAL, the wire format, `EventPayloadCodec` and SQL, and are never renamed. Gameplay concepts are layered on as state tables, `RuleRegistry` families and DTO fields keyed by those IDs. `CharacterId`, `HouseholdId`, `SiteId` and `HoldId` are historical names only.
- **D2:** `libs/procgen` returns when the first real generator lands (planned: Phase 1 Ticket 14, `NameGrammar` + `PersonaGenerator`), absorbing `WorldSeed`/`SeededRng` from `com.kotharworks.wilds.seed`. Until then the `com.kotharworks.wilds.worldgen` seams (`WorldTopologyBootstrap`, `DescriptionComposer`) are the only procgen surface; the seed primitives live in `libs/wilds` only temporarily.

---

## 2. Build Order & Dependency Graph

```text
  [10 Planning reconciliation & Phase 1 index]
     │
     ├──► [11 Rules-version gate]
     │       └──► [12 Items, stockpiles, carry & Deposit]
     │               └──► [13 Resource nodes, Gather & DEPLETED]
     │
     └──► [14 libs/procgen: NameGrammar, PersonaGenerator]
             └──► [15 3x3 region geography]

  Later tickets (each starts once every listed dependency is done):

   13 + 14 ──► [16 ProseComposer & JournalComposer]
   13 + 16 ──► [17 /play Character Deck]
   13 + 14 ──► [18 Skills, XP & stamina]
        18 ──► [19 Craft, Research & Construct]
        19 ──► [20 Tithe Ship, Favor, RecruitSettler]
        19 ──► [21 Hold rings, raid rumor, RaidResolver]
   15 + 18 ──► [22 Parties, lairs, muster, CombatDoctrine]
        22 ──► [23 ExpeditionResolver, permadeath, Cairn]
   20 + 21 ──► [24 apps/wilds-bot soak]

  [infra Keycloak deploy/ compose + live OIDC systest]  (independent)
```

| # | Ticket | Depends on | Milestone | Key (`st`) |
| :---: | :--- | :---: | :---: | :---: |
| 10 | Planning reconciliation & Phase 1 index *(this ticket)* — `done/10-planning-reconciliation-and-phase1-index.md` | — | — | `0fac031c` |
| 11 | Rules-version gate: `RulesFingerprint` pin, clean boot refusal & world rollover — [`11-rules-version-gate-and-world-rollover.md`](11-rules-version-gate-and-world-rollover.md) | 10 | M2 prep | `181c8f17` |
| 12 | Items, stockpiles, carry & `Deposit` | 11 | M1b | *planned* |
| 13 | Resource nodes, `Gather` ×N & fail-soft `DEPLETED` | 12 | M1b | *planned* |
| 14 | Restore `libs/procgen`: `NameGrammar`, `PersonaGenerator`, 3-settler charter | 10 | M1b | *planned* |
| 15 | Bounded 3×3 region geography, `WaypointGraph` & `headless gallery` | 14 | M1a | *planned* |
| 16 | `ProseComposer` (`glance`/`look`/`examine`) & `JournalComposer` | 13, 14 | M1b | *planned* |
| 17 | `/play` multi-tab Character Deck (persona, journal, stockpile, queue builder) | 13, 16 | M4b | *planned* |
| 18 | Skills, XP curve & stamina modifiers | 13, 14 | M2 | *planned* |
| 19 | Craft, Research & Construct (4 techs, 5 buildings) | 18 | M2 | *planned* |
| 20 | Tithe Ship heartbeat, Favor & `RecruitSettler` | 19 | M2 gate | *planned* |
| 21 | Hold rings, raid rumor & non-lethal `RaidResolver` | 19 | M3 | *planned* |
| 22 | Parties, `LairGraphGenerator`, lazy muster & `CombatDoctrine` | 15, 18 | M3 | *planned* |
| 23 | `ExpeditionResolver`, permadeath, scars & Memorial Cairn | 22 | M3 gate | *planned* |
| 24 | `apps/wilds-bot` `GreedyFrontierDoctrine` & 3-day soak | 20, 21 | M5a gate | *planned* |
| infra | Keycloak `deploy/` compose + live OIDC `systest` path | — | M4c | *planned* |

Only `10` and `11` carry `st` keys at this point; the rest are marked *planned*.

---

## 3. Ticket Spec Template (tickets 12+)

Each spec lives at `tickets/NN-<slug>.md`. The `st` description is `Spec file: tickets/NN-<slug>.md (see also tickets/README.md)` followed by the full spec, re-synced with `st edit` whenever the spec changes.

```markdown
# Ticket NN — <Title>

- **`st` key:** `<key>`
- **Status:** planned | in progress | done
- **Depends on:** <ticket numbers>
- **Modules touched:** <modules>

## 1. Objective
## 2. Explicit Non-Goals
## 3. Deliverables
## 4. Tests & Acceptance Criteria
## 5. Review Focus
```

Review Focus items are specific and falsifiable (about 5-10 items), and point at the seams where bugs are most likely to hide. See [`../AGENTS.md`](../AGENTS.md) (*Ticket specs & Review Focus*) and [`../prompts/deep-review.md`](../prompts/deep-review.md).

---

## 4. Testing Pyramid

Phase 0 layers remain in force:

1. **Architecture gates:** bytecode and reflection checks for purity, no-float and no unordered state iteration, on every build.
2. **Deterministic unit/property tests:** heap ordering, generation recycling, pure validation, randomized fuzz and golden `WorldHash` locks.
3. **Security gate:** `AuthSecurityGateTest` negative scenarios for routes, JWT, CSRF, seat spoofing and bot tokens.
4. **Loopback protocol ITs:** real-socket HTTP + SSE for idempotency, `409 STALE_STATE` and cursor catch-up.
5. **Postgres ITs:** Testcontainers migrations, OIDC linking and crash recovery.
6. **Full-spine E2E:** live server + Postgres + `/play` + SSE + crash/restart + headless `WorldHash` parity.

Phase 1 adds:

7. **Story tests:** headless scripted scenarios exercising a mechanic end to end.
8. **Conservation/invariant fuzz:** randomized command streams asserting that items and resources are conserved and invariants hold.
9. **Golden `WorldHash` + rules-version pin (Ticket 11):** any rule change must re-bless the hash and bump `WILDS_RULES_VERSION`.
10. **Live bot/human `systest`:** `./systest.sh` suites in the matching `systest/` package, in bot and emulated-human modes.

---

## 5. Definition of Done (Every Phase 1 Ticket)

- [ ] `mvnd test` (`~/.sdkman/candidates/mvnd/current/bin/mvnd test`, `-Xlint:all,-serial -Werror`) passes with zero warnings; `./systest.sh` passes.
- [ ] Apache 2.0 headers on new `.java`/`pom.xml`; `PROJECTS.md` updated for module/feature changes.
- [ ] Engine IDs stay neutral (`OwnerId`/`ActorId`/`PlaceId`); gameplay nouns are allowed in rule families, state tables keyed by those IDs, events, DTOs, UI and SQL.
- [ ] Any rule/mechanic change re-blesses the golden hash and bumps `WILDS_RULES_VERSION` (enforced by Ticket `11` once landed).
- [ ] New gameplay/API behaviour has a live test in the matching `systest/` package; user-visible features have a human-mode test; bug fixes have a regression test.
- [ ] Client responses go only through `ObservationProjector` DTOs (no `*Row` serialization); purity/no-float gates stay green.
- [ ] The spec has a `Review Focus` section, and a deep review (`prompts/deep-review.md`) has run before any dependent ticket starts; confirmed `[HIGH]` findings are fixed.
- [ ] Completed ticket moved to `tickets/done/`, committed, pushed, `st status <KEY> done`.
