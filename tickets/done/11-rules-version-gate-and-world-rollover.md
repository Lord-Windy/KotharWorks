# Ticket 11 — Rules-Version Gate: `RulesFingerprint` Pin, Clean Boot Refusal & World Rollover

- **`st` Key:** `181c8f17` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Done
- **Depends on:** Ticket `10` (`0fac031c`)
- **Modules touched:** `libs/wilds` (`rules`, `replay`), `apps/wilds-headless`, `apps/wilds-server` (`domain`, `ports`, `adapters/{memory,postgres,http}`, `Main`, `db/V004__*.sql`), `systest/`, `PROJECTS.md`

---

## 1. Objective

Every Phase 1 gameplay ticket changes rules. A world's WAL is only replayable by the rules it was written under, so each rule change must (a) bump `RuleRegistry.WILDS_RULES_VERSION`, and (b) be handled cleanly by servers whose database holds a world from an older version.

What already exists (do not rebuild):
- `WorldState.rulesVersion` (included in `WorldHash`), `WildsEngine.restore` rejects mismatched snapshots.
- `WalHeader(worldSeed, rulesVersion, engineVersion)`; `wilds-headless replay` exits `2` on header mismatch.
- `wilds_meta.worlds` pins `(world_seed, rules_version, engine_version, protocol_version)` with an `ACTIVE`/`ARCHIVED` `status` and a one-active partial unique index; `ensureActiveWorld` throws a bare `IllegalStateException` on mismatch, which `Main` does not catch (stack trace).

What is missing, and this ticket delivers:
1. **Mechanical enforcement** that a rule/mechanic change cannot ship without a version bump (`RulesFingerprint` + `RulesVersionPinTest`), turning review-checklist item 6 into a build failure.
2. **An extensible `RuleRegistry`** whose content families serialize through **one** canonical writer, so Phase 1 adds item/skill/tech/building families in one place and they automatically reach the fingerprint and `GET /api/v1/rules`.
3. **A clean server boot refusal** on world/binary mismatch, plus an explicit, non-destructive **rollover** (`--on-version-mismatch=archive`) that archives the old world and starts a fresh one, carrying permanent seats across.

---

## 2. Explicit Non-Goals

- **No new rule families** (items, skills, techs…) and no gameplay. `WILDS_RULES_VERSION` stays `1`; the golden hash (`minimal-spine.hash`) must be **unchanged** by this ticket.
- No WAL migration/upcasting between rules versions — old worlds are archived, never converted.
- No deletion of any `wilds_world` rows. Archived worlds' WAL, checkpoints and events stay intact and exportable.
- `RulesFingerprint` is **not** folded into `WorldHash` (it describes the binary's rules, not world state).

---

## 3. Deliverables

### 3.1 Extensible `RuleRegistry` + Canonical Rules Writer (`libs/wilds` `rules`)

- Introduce `sealed interface RuleDef permits PlaceKindDef, TaskKindDef` (each with `RuleId id()`). Phase 1 extends the `permits` list.
- Replace the two-map constructor with `RuleRegistry.builder()` (`.placeKind(def)`, `.taskKind(def)`, `.build()`), rejecting duplicate ids within a family and keeping ascending-`RuleId` `TreeMap` storage. Keep `minimalDefault()` with identical content; update all callers (`WildsEngine`, tests, headless, server).
- Add `RulesJson` (in `rules` or `replay`): the **single** canonical `JsonCodec` writer for the full catalog, families in a fixed order, entries ascending by `RuleId`, using an exhaustive `switch` over `RuleDef` so a new family that is not serialized fails compilation.
- `GET /api/v1/rules` must render via `RulesJson` (delete the hand-written serializer in `ObservationJson.rules`). The response stays backward compatible — same `rulesVersion`, `placeKinds`, `taskKinds` field names and values — and gains `"rulesFingerprint": "0x<16 hex>"`.

### 3.2 `RulesFingerprint` (`libs/wilds` `rules`)

- `long RuleRegistry.fingerprint()` = first 8 bytes (big-endian) of SHA-256 over the UTF-8 bytes of `RulesJson`'s canonical output **excluding** `rulesVersion`/`rulesFingerprint` themselves. Reuse the existing SHA-256 approach from `WorldHash` (no new dependency).
- Format helper `RulesFingerprint.toHex(long)` → `0x` + 16 lowercase hex digits, shared by headless, server and tests.

### 3.3 `RulesVersionPinTest` (`libs/wilds` test)

- Extract the scripted scenario from `GoldenReplayTest` into a shared test helper (e.g. `GoldenScenario.run()`) so both tests compute the golden hash independently of test ordering.
- Pin file `libs/wilds/src/test/resources/golden/rules-version.pin`:
  ```text
  rulesVersion=1
  rulesFingerprint=0x<16 hex>
  goldenHash=<decimal, same value as minimal-spine.hash>
  ```
- Put the decision logic in a pure, unit-tested function (e.g. `RulesPin.evaluate(pinned, current, blessing)`):
  - pin == current → pass.
  - fingerprint or golden hash differs **and** `WILDS_RULES_VERSION == pinned.rulesVersion` → **fail** with: *"Rules or mechanics changed without bumping RuleRegistry.WILDS_RULES_VERSION (pinned N). Bump it, then run `mvnd test -pl libs/wilds -DblessGolden=true`."*
  - anything differs and not blessing → fail, telling the developer to bless.
  - blessing (`-DblessGolden=true`) rewrites the pin **only** when the version was bumped or nothing but the version changed; it refuses to bless a content change at an unchanged version.
- Document in the test Javadoc that a change to the golden *scenario itself* also forces a bump — versions are cheap and dev worlds are disposable until Chronicle I.

### 3.4 WAL Header & Headless (`libs/wilds` `replay`, `apps/wilds-headless`)

- `WalHeader` gains optional `rulesFingerprint` (`Long`, nullable). Encode it when present; decode headers with or without it (existing exported WALs keep working).
- `wilds-headless run --out-wal` writes the fingerprint. `replay` fails with exit code `2` and a clear diagnostic when a present fingerprint differs from the running binary's, exactly like the existing version check.

### 3.5 World Metadata & Boot Refusal (`apps/wilds-server`)

- **`V004__worlds_rules_fingerprint.sql`:** `ALTER TABLE wilds_meta.worlds ADD COLUMN rules_fingerprint BIGINT` (nullable; `wilds_meta` is not an append-only schema). `WorldRecord` gains `Long rulesFingerprint`; both stores populate it on create.
- `ensureActiveWorld(...)` additionally takes the running fingerprint. Mismatch check = seed, rules/engine/protocol versions, and fingerprint **when the stored value is non-null** (legacy rows with `NULL` match on versions alone).
- Replace the bare `IllegalStateException` with a typed `domain/WorldVersionMismatchException` carrying the stored vs. running `(seed, rulesVersion, engineVersion, protocolVersion, rulesFingerprint)`.
- `Main` catches it, prints one human-readable diagnostic to stderr (stored vs. running values, and the two remediations: run the matching binary, or restart with `--on-version-mismatch=archive`), and exits with **code `3`** — no stack trace.
- `/api/v1/meta` adds `rulesFingerprint` (hex) next to the existing version fields.

### 3.6 Rollover: `--on-version-mismatch=refuse|archive` (default `refuse`)

- New `ServerConfig` option parsed by `Main` (both `--flag value` and `--flag=value` forms, consistent with existing flags).
- `archive` mode, inside the **same** `pg_advisory_xact_lock` transaction as `ensureActiveWorld`: `UPDATE` the mismatched world to `status='ARCHIVED', ended_at=now()`, then `INSERT` the new `ACTIVE` world. No `wilds_world` rows are touched. Log one line naming the archived and new `world_id`. `InMemoryWorldStore` implements the same semantics for port parity.
- **Seat carry-over:** seats are permanent (`wilds_meta.seats` is not world-scoped), but their `RegisterOwner` lives in the archived world's WAL. On a rollover boot, after the fresh engine is up and before the HTTP listener accepts traffic, `WorldHost` submits `RegisterOwner` for every existing seat **through the normal mailbox path** (WAL-logged, Commit-Before-Mutate, no backdoor), in ascending `seat_id` order, using the same owner/initial-actor naming the seat-charter route uses. A failure for one seat is logged and leaves that seat on the existing `/play` "Retry registration" path; it must not abort boot.
- `refuse` mode behaviour is exactly §3.5.

---

## 4. Tests & Acceptance Criteria

**`libs/wilds`**
- `RuleRegistryTest`: builder rejects duplicate ids; iteration is ascending regardless of insertion order; `minimalDefault()` content unchanged.
- `RulesFingerprintTest`: stable across insertion orders and repeated runs; changes when any single field of any def changes (mutate each field once); pinned literal for `minimalDefault()`.
- `RulesJsonTest`: byte-for-byte pinned output for `minimalDefault()`.
- `RulesPinTest` (pure logic): all four branches of §3.3, including "blessing refuses a content change at an unchanged version".
- `RulesVersionPinTest` green against the committed pin; `GoldenReplayTest` hash **unchanged**.
- `WalHeaderTest`: round-trip with and without `rulesFingerprint`; legacy header (no field) decodes.

**`apps/wilds-headless`**
- `HeadlessCliTest`: `run --out-wal` header contains the fingerprint; `replay` of a WAL whose header fingerprint is tampered exits `2` with a fingerprint diagnostic; a legacy header without the field still replays.

**`apps/wilds-server`**
- `GET /api/v1/rules` response is unchanged apart from the added `rulesFingerprint`, which equals `/api/v1/meta`'s.
- Postgres IT (Testcontainers), **refuse**: pre-insert an `ACTIVE` world whose `rules_version = WILDS_RULES_VERSION + 1` → boot throws `WorldVersionMismatchException`; zero rows written to `wilds_world`; `Main`-level test asserts exit code `3`, diagnostic on stderr, no stack trace.
- Postgres IT, **archive**: boot world A with two seats and some commands; restart with a different `--seed` and `--on-version-mismatch=archive` → A is `ARCHIVED` with `ended_at` set; new world B is `ACTIVE`; A's `command_wal`/checkpoints/events row counts are unchanged and A's WAL still exports and replays to its last hash; B's WAL begins with `RegisterOwner` for both seats in `seat_id` order; both seats' `/api/v1/observation` show an owner in B; B's export replays in `wilds-headless` to B's live `WorldHash`.
- Legacy row with `rules_fingerprint IS NULL` and matching versions boots normally.

**`systest/`**
- Bot-mode live test: `/api/v1/rules` and `/api/v1/meta` expose matching `rulesVersion` and well-formed `rulesFingerprint`.
- Restart-phase live test: restart the server with a different `--seed` → container exits `3`; restart again with the different seed plus `--on-version-mismatch=archive` → boots, the pre-existing bot seat has an owner in the new world, `/api/v1/meta` shows the new seed. If the restart harness cannot yet vary server arguments, extend `systest.sh`/compose to allow it (black-box only — no server/engine dependency from `systest`).

**Definition of Done**
- `mvnd test` zero warnings; `./systest.sh` passes; Apache headers on new files.
- `PROJECTS.md` updated (`RulesFingerprint`, `RulesJson`, builder, `V004`, `--on-version-mismatch`, exit code `3`).
- `06-VerticalSliceAndRoadmap.md` §4.3 item 6 references `RulesVersionPinTest` (if Ticket `10` left a placeholder).
- Ticket file moved to `tickets/done/`, committed, pushed, `st status 181c8f17 done`.

---

## 5. Review Focus

Where this ticket is most likely to be wrong. The reviewer gives each item a verdict (bug / proven safe / undetermined), backed by a test, command or line-by-line trace (see `prompts/deep-review.md`).

1. **Pin bypass.** Can a rules or mechanics change reach `main` without a version bump? Try: (a) blessing a changed fingerprint at an unchanged version; (b) a run where `RulesVersionPinTest` executes before `GoldenReplayTest` blesses; (c) a mechanic change that alters behaviour but not the `minimalDefault()` catalog, so only the golden hash catches it — does the pin compare the golden hash it *computes*, or a file that might be stale?
2. **Fingerprint canonicality.** Does `RulesJson` output depend on `HashMap` order, `Map.of` iteration, locale, or `Long.toString` of a value that could become negative? Does each field change actually move the fingerprint (no field silently left out of `RulesJson`)? Is `rulesVersion` really excluded from the fingerprint input?
3. **`/api/v1/rules` compatibility.** Byte-compare the old and new response for `minimalDefault()`: same field names, order and values, plus only `rulesFingerprint`. A systest bot or `wilds-ui.js` parsing the old shape must still work.
4. **Golden hash untouched.** `minimal-spine.hash` and `WILDS_RULES_VERSION` must be unchanged in the diff. If either moved, why?
5. **Archive atomicity.** Are the `UPDATE ... ARCHIVED` and `INSERT` of the new world inside one transaction under `pg_advisory_xact_lock`? What happens if the server dies between them, or two servers boot with `archive` at once — can the one-active partial unique index be violated, or both worlds end up archived?
6. **Nothing in `wilds_world` touched.** Confirm by row counts in the IT, and read the SQL: no `DELETE`/`UPDATE` against `command_wal`, `world_checkpoints` or `world_events`. Can the archived world still be exported and replayed?
7. **Seat re-registration ordering and backdoors.** Does re-registration go through the `WorldHost` mailbox with Commit-Before-Mutate (WAL row per seat), not direct engine calls? Does it finish before the HTTP listener accepts traffic, or can a player's command race it? Is `seat_id` order actually enforced (SQL `ORDER BY`, not map order)? Does one failing seat abort boot?
8. **Refuse path leaves no trace.** On mismatch in `refuse` mode: zero writes to any schema (including `wilds_meta.worlds`), exit code exactly `3`, no stack trace, and the connection pool / threads are closed so the process actually exits.
9. **Legacy compatibility.** A `worlds` row with `rules_fingerprint IS NULL`, and a WAL header without the field, must both keep working. Conversely, is a *present* fingerprint mismatch ever ignored?
10. **Flag parsing.** `--on-version-mismatch=archive` and `--on-version-mismatch archive` both work; an unknown value fails with exit `2` (existing usage-error convention), not a silent default to `refuse`.
