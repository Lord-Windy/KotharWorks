# Review Ticket: {{ticket.id}}

## Summary
{{ticket.summary}}

## Description
{{ticket.description}}

## Your task

Review the implementation against the ticket and **fix any issues you find**.
Don't stop to ask — categorize, fix, commit, push.

## Checks

### 1. Ticket fulfillment
Verify every requirement from the description is actually implemented. Run
`st show {{ticket.id}} --json` if you need the full ticket payload. If the
description references a spec file under `tickets/`, read it.

### 2. Duplicated effort
Search the codebase for records, interfaces, or helpers that overlap with what
this ticket added. KotharWorks keeps its engine primitives in `libs/` (see
`PROJECTS.md`). If the new code reimplements something that exists, replace
the duplicate with the existing API.

### 3. File and module structure
Confirm the change respects KotharWorks' layout (see `AGENTS.md`):
- Library code lives in `libs/<name>/`, applications in `apps/<name>/`.
- New modules are registered in the root `pom.xml` (`<modules>`, plus
  `<dependencyManagement>` for shared libraries) and in `PROJECTS.md`.
- `libs/*` modules have no web/DB/IO dependencies and stay deterministic:
  no wall-clock calls, no `Math.random()`/`ThreadLocalRandom`, no I/O —
  logical time and seeded RNGs are passed in.
- Hexagonal layering (`domain/`, `ports/`, `adapters/`, `commands/`) only in
  `apps/*`; plain packages in `libs/*`.
- New `.java`/`pom.xml` files carry the Apache 2.0 license header
  ("Samuel \"Lord-Windy\" Brown", year of creation).
- Simulation state uses flat entity tables addressed by typed value IDs —
  no mutable object references across entities.
- No Kotlin, Lombok, Spring, Hibernate, or annotation processors anywhere.

### 4. Code quality
- Tests exist for the new behaviour and pass.
- No dead code, no commented-out blocks, no comments narrating what code does.
- No error handling for impossible cases; no premature abstraction.
- No unrelated cleanup mixed into the ticket.

### 5. Edge cases
Look for inputs the implementation didn't account for: empty inputs, large
inputs, null/absent optionals, repeated commands, generation-wrap on typed IDs.

## Severity and action

| Severity | Examples                                                  | Action |
|----------|-----------------------------------------------------------|--------|
| Minor    | typos, style, missing edge case, doc tweak, small dup     | Fix    |
| Major    | purity/determinism violation, structural violation, missing requirement, large duplication, broken test | Fix    |

**Always fix. Never ask.**

## Workflow

1. Read the diff (`git log -p` or `git diff` against the branch base).
2. Run the checks above.
3. Fix everything you found.
4. `mvnd test` to verify — it must pass with zero warnings.
5. Commit and push if you changed anything.
