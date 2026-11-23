# AGENTS.md — KotharWorks Agent Guide

This file instructs AI coding agents on how to build, structure code, and use
`samtasker` (`st`) to manage tasks within **KotharWorks** (`com.kotharworks`).

## Project setup

This git repository is the project. Use **`KOTHARWORKS`** (UPPERCASE,
alphanumeric) as the `--project` identifier when creating or listing tasks in
`st` (unless the user explicitly specifies `PTAH` for a cross-repo epic). Assume
`samtasker` (`st`) is already initialized and configured by the user.

## Project layout

KotharWorks is a **pure Java 25 LTS** multi-module Apache Maven monorepo.
Reusable libraries live in `libs/` and runnable applications live in `apps/`.

```text
kotharworks/
├── pom.xml                  # Root Maven parent & reactor (<modules>)
├── .mvn/mvnd.properties     # Pins mvnd daemon to JDK 25 (25.0.3-tem)
├── libs/
│   └── <name>/              # Pure library module (com.kotharworks:<name>)
└── apps/
    └── <name>/              # Application module (com.kotharworks:<name>)
```

For per-module descriptions, status, and dependencies, see
[PROJECTS.md](PROJECTS.md).

## Build System

Use **`mvnd`** (Apache Maven Daemon, installed via SDKMAN at
`~/.sdkman/candidates/mvnd/current/bin/mvnd`). `.mvn/mvnd.properties`
automatically boots the daemon on JDK 25 without altering global `JAVA_HOME`.
Never introduce Gradle or Kotlin build files.

### Common Commands

```bash
# Compile and run all tests across the monorepo
mvnd test

# Test a single module and its upstream monorepo dependencies
mvnd test -pl libs/wilds -am

# Run a single test class
mvnd test -pl libs/wilds -Dtest=WildsEngineTest

# Execute an application module directly from reactor classes
mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-server -am

# Clean all target/ directories
mvnd clean

# Full gate in Docker: unit + integration suite, then live bot/human system suite
./systest.sh
./systest.sh live --test BotProtocol --keep   # iterate on one live test class
```

### System test suite (`systest/`)

`./systest.sh` is the one command every developer and agent runs before calling work
done; it needs only Docker. It runs the regular Maven suite, then boots
`wilds-server` + PostgreSQL in Docker and drives it with scripted bots (REST/SSE) and
emulated humans (headless Chromium), including a kill -9 recovery phase. Reports and
failure artifacts (screenshots, traces, logs) land in `systest/reports/`.

- New gameplay/API behaviour gets a live test in the matching `systest/` package.
- **Every bug fix gets a regression test** in `systest/.../regression/` (or a unit test
  in the owning module when it is not observable black-box), naming the commit/ticket.
- Live tests are black-box: never add a dependency from `systest` on server/engine code.
- See [systest/README.md](systest/README.md) for conventions (shared world, seat
  names, `Await` instead of sleeps, human vs bot helpers).

### Adding a new module

1. Create `libs/<name>/pom.xml` or `apps/<name>/pom.xml` inheriting from
   `com.kotharworks:kotharworks-parent`
   (`<relativePath>../../pom.xml</relativePath>`).
2. Add `<module>libs/<name></module>` (or `apps/<name>`) to root `pom.xml`.
3. If it is a shared library, register its coordinates in root `pom.xml`
   `<dependencyManagement>`.
4. Register the new module in [PROJECTS.md](PROJECTS.md).

## Coding & Architectural Standards (Java 25 LTS)

These rules exist specifically to keep code fast, readable, deterministic, and
free of framework bloat:

1. **Pure Java 25 LTS only.** Do not add Kotlin, Lombok, Spring Boot,
   Hibernate/JPA, or annotation-processor magic. Warnings are errors
   (`-Werror`).
2. **Data-Oriented Java (OOP Patterns + FP):**
   - Use immutable `record`s for commands, events, value IDs, rule definitions,
     and fogged observation DTOs.
   - Use `sealed interface` + exhaustive `switch` expressions for closed
     command, event, and result families so the compiler catches unhandled
     cases.
   - Use clean `@FunctionalInterface`s or small classes for Strategy seams
     (policies, doctrines, ports).
3. **Flat Entity Tables + Typed IDs (No Object-Reference Webs):**
   - Inside simulation state, store entities in flat indexed collections/maps
     addressed by value IDs (e.g.
     `record CharacterId(int index, int generation)` or monotonic `long` IDs) —
     **never** hold mutable object references across entities (`Character`
     holding a direct `Party` or `Site` reference). This keeps state trivially
     serializable, deterministic, and immune to aliasing bugs.
4. **Pure Simulation Libraries (`libs/*`):**
   - Simulation and procgen libraries must remain **100% pure and
     deterministic**: no `System.currentTimeMillis()` / `Instant.now()`, no
     `Math.random()` / `ThreadLocalRandom`, no file/network/SQL I/O, and no
     web/DB dependencies in their `pom.xml`. Logical time and seeded RNGs are
     always passed in.
5. **Concurrency — Virtual Threads, Zero Reactive Soup:**
   - Do not use `CompletableFuture` chains, WebFlux, or reactive streams. Use
     Java 25 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`)
     with straightforward synchronous code and explicit serialization around
     mutable world state.
6. **When to use Hexagonal Architecture:**
   - **Applications (`apps/*`):** Yes — separate `domain/` (or service),
     `ports/`, `adapters/` (HTTP/HTML, Postgres/JDBI, clock), and `commands/`.
   - **Libraries (`libs/*`):** Default to plain, cohesive packages. Do not
     invent ports/adapters inside a pure library unless two real implementations
     exist.

## License Requirements

**All source files (`.java`, `pom.xml`) must include the Apache License 2.0
header with copyright to Samuel "Lord-Windy" Brown.** Use the year the file was
created; do not bump existing files when editing them.

For Java files (`.java`):

```java
/*
 * Copyright 2026 Samuel "Lord-Windy" Brown
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

## Creating tickets (`st`)

### Epic labels

Every ticket carries the label of the epic it belongs to, in the form
`tech-<epic>` — e.g. `tech-wilds`, `tech-procgen`. `samtechtree` counts an
epic's tickets by this label, so a ticket without it never reaches its epic's
node. Use the epic's slug from the tree; if it is unclear which epic a ticket
belongs to, ask.

`st` labels may contain only lowercase letters, digits, and hyphens, at most 64
characters (`tech:wilds` is rejected — use a hyphen).

### Via CLI flags

```bash
st add --project KOTHARWORKS --summary "Implement due-event min-heap" \
  --description "Deterministic (dueMs, seq) priority queue in libs/wilds" \
  --labels tech-wilds,sim
```

### Via JSON (single)

```bash
echo '{"project":"KOTHARWORKS","summary":"Implement due-event min-heap","description":"Deterministic priority queue","labels":["tech-wilds","sim"]}' | st add --json
# Output (stdout): {"message":"Created task: a1b2c3d4"}
```

### Via JSON (batch)

```bash
cat <<'EOF' | st add --json
[
  {"project":"KOTHARWORKS","summary":"Design Postgres schemas","labels":["tech-wilds","db"]},
  {"project":"KOTHARWORKS","summary":"Build JDBI adapters","labels":["tech-wilds","db"]}
]
EOF
```

Batch caveats:

- **The exit code is 0 even if some items fail.** Check output for `"error"`
  lines.
- **A reported-failed item may still have been created** if only dependency
  linking failed. Run `st list --project KOTHARWORKS --json` before retrying.

### Ticket specs & Review Focus

Any ticket bigger than a one-line fix gets a spec file `tickets/NN-<slug>.md`
with these sections: header (`st` key, status, depends on, modules touched),
`1. Objective`, `2. Explicit Non-Goals`, `3. Deliverables`,
`4. Tests & Acceptance Criteria`, and **`5. Review Focus`**. The `st`
description is `Spec file: tickets/NN-<slug>.md (see also tickets/README.md)`
followed by the full spec text. Whenever the spec file changes, re-sync it with
`st edit <KEY> --description ...`, because the pipeline only sees the `st`
description.

**Review Focus** is written by whoever writes the ticket, at the moment they
know best where it's risky. It lists the specific ways this ticket is most
likely to be wrong, so the reviewers (`prompts/review.md` in the pipeline,
`prompts/deep-review.md` for the human-gated pass) check those first instead of
reviewing generically. Good items are:

- **Specific and falsifiable:** a concrete failure scenario a reviewer can prove
  or rule out with a test, command, or trace (e.g. "server dies between the
  `ARCHIVED` update and the new-world insert: are both worlds archived?").
  Avoid "check error handling" or "make sure it's secure".
- **Pointed at the seams:** failure ordering (Commit-Before-Mutate), concurrency
  and boot/shutdown races, determinism and rules-version bumps, fog-of-war leaks,
  auth/CSRF/XSS, backward compatibility of wire/WAL/SQL formats, and whether the
  tests would still pass with the feature deleted.
- **About 5–10 items.** Include anything the spec deliberately left to the
  implementer's judgement.

Implementers treat Review Focus items as risk areas to cover with tests. When
the review finds a bug the focus list missed, add that class of mistake to
future tickets' focus lists.

## Working on & completing tickets

1. Find ready work:
   ```bash
   st list --ready --project KOTHARWORKS --json
   st show <KEY> --json
   ```
2. Mark `in-progress`:
   ```bash
   st status <KEY> in-progress
   ```
3. Implement and verify `mvnd test` passes cleanly with zero warnings, then run
   `./systest.sh` (Docker) and make sure it passes.
4. **Commit and push** before marking `done` (once a remote is configured):
   ```bash
   git add <files>
   git commit -m "Implement feature X"
   git push
   st status <KEY> done
   ```
5. **Deep review before dependents start.** Run the human-gated review in
   [`prompts/deep-review.md`](prompts/deep-review.md) (`TICKET: <KEY>`,
   `ACTION: report-only`) and fix the confirmed `[HIGH]` findings before any
   ticket that depends on this one begins. Don't let Phase 0's mistake repeat:
   reviewing nine stacked tickets after the fact made the fixes cascade.
