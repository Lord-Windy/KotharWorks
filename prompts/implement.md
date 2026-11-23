# Implement Ticket: {{ticket.id}}

## Summary
{{ticket.summary}}

## Description
{{ticket.description}}

## Project context

This is **KotharWorks**, a pure Java 25 LTS multi-module Apache Maven monorepo
(`libs/` for libraries, `apps/` for runnable applications). Read `AGENTS.md`
and `PROJECTS.md` for the full picture. The non-negotiables:

- **Pure Java 25 only.** No Kotlin, Lombok, Spring Boot, Hibernate/JPA, or
  annotation-processor magic. Warnings are errors (`-Werror`).
- **Build with `mvnd`.** Focused run: `mvnd test -pl <module> -am`; whole
  monorepo: `mvnd test`.
- **Data-Oriented Java.** Immutable `record`s for commands, events, value IDs,
  rule definitions, and DTOs. `sealed interface` + exhaustive `switch`
  expressions for closed command/event/result families. Small
  `@FunctionalInterface`s for Strategy seams.
- **Flat entity tables + typed IDs.** Simulation state stores entities in flat
  indexed collections addressed by value IDs — never mutable object references
  across entities.
- **`libs/*` stay 100% pure and deterministic.** No `Instant.now()` /
  `System.currentTimeMillis()`, no `Math.random()` / `ThreadLocalRandom`, no
  file/network/SQL I/O, no web/DB dependencies. Logical time and seeded RNGs
  are always passed in.
- **Concurrency:** Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`)
  with synchronous code in `apps/*`. No `CompletableFuture` chains or reactive
  streams.
- **Hexagonal architecture in `apps/*` only** (`domain/`, `ports/`, `adapters/`,
  `commands/`). Libraries use plain cohesive packages.
- **License header:** every new `.java`/`pom.xml` gets the Apache 2.0 header for
  "Samuel \"Lord-Windy\" Brown" with the year the file was created.
- **Don't over-engineer.** No ports/adapters inside a library unless two real
  implementations exist. No error handling for cases that can't happen. No
  comments explaining what well-named code already says.

## Before you write code

**Search the codebase for existing functionality first.** KotharWorks already
has a simulation core, seed/RNG machinery, and supporting libraries under
`libs/`. Check `PROJECTS.md` and the existing `libs/*/src` trees before writing
anything that smells like infrastructure. If something close exists, extend or
reuse it instead of duplicating.

If the ticket description references a spec file under `tickets/` or
`planning/`, read it — the description is usually an excerpt of a longer spec.

The spec's **`Review Focus`** section lists where reviewers will look hardest.
Treat each item as a risk to cover with a test while you implement, not
something to leave for the review to find.

## Your task

1. Search for related/overlapping code so you don't duplicate effort.
2. Implement the change, scoped tightly to this ticket.
3. Add or update tests in the relevant module's `src/test`.
4. Run `mvnd test` and make it pass with zero warnings, then `./systest.sh`
   (Docker) for server, API, or UI changes.
5. Commit and push when green.
