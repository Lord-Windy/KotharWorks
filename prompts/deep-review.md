TICKET: <st key, e.g. 181c8f17 — or `cross-cutting`>
ACTION: report-only

# KotharWorks Deep Review (human-gated)

You are doing a deep, skeptical, line-by-line engineering and architecture review of
one KotharWorks ticket. The code was written by an automated pipeline, and the
pipeline's own review (`prompts/review.md`) has already run. Assume it missed things.
This review is the human-gated one: find what is actually wrong, prove it, and report
it so the owner decides what changes.

Review only the ticket named in `TICKET`, and do what `ACTION` says:

- `report-only`: **No edits to tracked files.** You may write throwaway tests or
  scripts under the session scratchpad (or another untracked temp directory) to
  prove findings. Deliver the report.
- `fix-no-commit`: Fix the findings listed in `FIX:` (see §5) — or, when no `FIX:`
  line is given, every confirmed `[HIGH]`/`[MEDIUM]`. Every fix gets a regression
  test. Verify (§3 step 6) and leave changes uncommitted for `git diff`.
- `fix-and-commit`: Same as `fix-no-commit`, then commit on the current branch with
  a message naming the ticket key and the finding IDs fixed. Do not push unless asked.

An optional third header line `FIX: 11-H1, 11-M2` limits the fix modes to those
findings from an earlier `report-only` run.

---

## 1. Resolve the Ticket

1. `st show <TICKET> --json`. The description starts with `Spec file: tickets/...md`;
   read that spec in full (it may be in `tickets/` or `tickets/done/`).
2. Read the spec's **`Review Focus`** section. Those items are the author's guesses
   about where this ticket is most likely broken. **Every item must get an explicit
   verdict in your report** (confirmed bug / proven safe / could not determine, and
   why). They are the floor, not the ceiling — keep looking past them.
   If the spec has no `Review Focus` section, say so as a `[LOW]` finding and write
   the list you would have wanted before starting.
3. Read the planning docs the spec references (`planning/ironwood_wilds_planning/`),
   plus `AGENTS.md` and `PROJECTS.md`.
4. Find the commits: `git log --oneline --grep=<TICKET>` (pipeline commits are
   prefixed with the key; older ones say `Ticket NN` or `Pass NN review`). Use
   `git show --stat` only to *map* which files the ticket introduced or touched.

## 2. Scope Discipline

- **Read the current files on `HEAD` in full**, not just the diffs. Later tickets
  often edit earlier code; review what exists now and attribute each finding to the
  ticket that introduced it when you can.
- **Large scope:** if the ticket's production + test code is more than roughly 3,000
  lines, split the reading by area (e.g. engine / persistence / HTTP / UI) and
  delegate each area's file reading to a subagent with the relevant `Review Focus`
  items. Keep the cross-cutting reasoning (concurrency, failure ordering, data flow
  between areas) yourself. Do not let the last files get skimmed.
- Stay in scope. Pre-existing problems in other tickets' code go in a short
  "Out of scope, noticed" list at the end, not in the findings.

## 3. Methodology

1. Spec first: list every deliverable, flag, status code, invariant and acceptance
   criterion before reading code.
2. Read the code and tests (§2).
3. Work through every `Review Focus` item.
4. Evaluate against all gates in §4.
5. **Prove before you claim.** A finding is `[HIGH]` only if you show it: a failing
   test, a command and its output, or a step-by-step trace through exact lines that
   leaves no doubt. If you cannot demonstrate it, it is `[QUESTION]` (or `[MEDIUM]`
   with the doubt stated) — never a confident `[HIGH]`. Don't confirm a hypothesis
   just because the spec or the focus list suggested it.
6. Run the checks yourself:
   - `~/.sdkman/candidates/mvnd/current/bin/mvnd test -pl <module> -am` while
     investigating; full `mvnd test` (zero warnings) before concluding.
   - `./systest.sh` (Docker) before concluding any fix mode, and in `report-only`
     when the ticket changes server, API or UI behaviour. If Docker is unavailable,
     say so explicitly — do not silently skip it.

## 4. Gates (every ticket)

1. **Spec completeness:** every requirement, flag, status/exit code, invariant and
   acceptance test exists and is actually exercised.
2. **Determinism & purity (`libs/*`):** no wall-clock, unseeded RNG, I/O, floats in
   state, or hash-order iteration; architecture gates still meaningful (not
   weakened to pass).
3. **Rules versioning:** any rule/mechanic change bumps `WILDS_RULES_VERSION`, re-blesses
   golden hashes, and keeps `RulesVersionPinTest` honest (once Ticket 11 lands).
4. **Flat tables + generational IDs:** no cross-entity object references; stale
   generational IDs rejected everywhere they can arrive (engine, wire, UI).
5. **Single writer & Commit-Before-Mutate (`apps/wilds-server`):** WAL commits before
   any `WorldState` mutation or SSE emit, under every failure point; no UI-private
   mutation paths; zero `UPDATE`s in `wilds_world`.
6. **Fog-of-war & DTO isolation:** clients see only `ObservationProjector` DTOs; no
   `*Row` serialization, undiscovered places/edges, foreign actor data, or private
   event text on public surfaces.
7. **AuthN/AuthZ, CSRF, XSS, DoS:** every route has an explicit `AccessPolicy`;
   bounded request bodies; escaping by default; scoped tokens can't move sideways.
8. **Gameplay invariants (Phase 1+):** conservation (no item created or destroyed
   outside a rule), no negative counts, non-lethal raids stay non-lethal, story
   tests cover the new loop.
9. **Test rigor:** flag tautological assertions, tests that would pass with the
   feature deleted, missing negative/edge tests, and `Thread.sleep` timing
   assumptions (systest uses `Await`). Every bug fix since the ticket landed has a
   regression test (`AGENTS.md`).
10. **Hygiene:** Apache 2.0 headers, `PROJECTS.md` accurate, spec moved to
    `tickets/done/`, `st` status matches reality.

## 5. Required Output

Four concise sections:

1. **What Was Built (1-minute walkthrough):** the classes/files in this ticket and
   how data and control flow through them. The owner hasn't read this code; orient
   them fast.
2. **Scorecard:** compact pass/fail against every spec deliverable and acceptance
   criterion, every `Review Focus` item (with verdict), and the §4 gates.
3. **Findings, ordered by severity.** Give each a stable ID `<ticket-number>-<letter><n>`
   (e.g. `11-H1`, `11-M2`, `11-L1`, `11-Q1`):
   - `[HIGH]` proven correctness, determinism, crash-recovery, security/fog,
     hang/DoS bug, or missing spec requirement.
   - `[MEDIUM]` edge-case bugs, race windows, weak/flaky tests, forward-compatibility
     friction with the planning suite.
   - `[LOW]` clarity, duplication, docs/header nits.
   - `[QUESTION]` a genuine trade-off needing the owner's call, or a suspicion you
     could not prove.
   Each finding cites `path/to/File.java:line`, the concrete failure scenario, and
   the proof (test name/command/trace) for `[HIGH]`.
4. **Verification & Next Step:** commands run and their results, fixes applied (fix
   modes), and a ready-to-edit fix list the owner can trim and paste back:
   ```text
   FIX: 11-H1, 11-H2, 11-M1
   ```
   Then state whether dependents of this ticket are clear to start.

---

## Variant: `TICKET: cross-cutting`

Run at the end of a phase instead of a single ticket. Scope: the whole monorepo
(`pom.xml`, `PROJECTS.md`, `AGENTS.md`, `tickets/`, `libs/*`, `apps/*`, `systest/`,
`planning/`). Checks:

1. **Duplication & drift:** overlapping JSON helpers, codecs, WAL serializers, wire
   parsers, or test utilities across modules.
2. **Concurrency & shutdown:** thread lifecycle across `AuthenticatedRouter`,
   `WorldHost`, `ClockPump`, SSE subscribers and the connection pool; clean shutdown
   with no leaked threads or lost final checkpoint/anchor.
3. **Forward compatibility:** does the current code paint the next phase's planned
   tickets (see `tickets/README.md`) into a corner?
4. **Compliance:** headers, `PROJECTS.md` vs reality, every completed ticket in
   `tickets/done/` and `done` in `st`, full `mvnd test` and `./systest.sh` green.
5. **Live smoke:** boot `wilds-server --storage memory --auth-mode dev-local` on an
   ephemeral localhost port and walk `/dev/login` → `/play` → claim seat → enqueue
   tasks → SSE → `/chronicle`.

Use finding IDs prefixed `X-` (e.g. `X-H1`).
