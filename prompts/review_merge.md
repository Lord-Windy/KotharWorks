# Post-Merge Review — Iteration {{iteration}}

## Feature branch
{{feature_branch}}

## Tickets merged this iteration
{{#tickets}}
- **{{key}}**: {{summary}}
{{/tickets}}

## Your task

The above tickets were just merged into `{{feature_branch}}`. Verify the
result is sound, fix what's broken, and create follow-up tickets for
structural issues that are out of scope to fix here.

## Step 1 — Pull the ticket data

For **each** ticket merged this iteration, run:

```bash
st show <KEY> --json
```

You need the full description, not just the summary. The merge has to
actually deliver what the ticket asked for, and the only way to confirm
that is to read the ticket alongside the diff.

## Step 2 — Verify the merge

For each ticket, compare the ticket's intent against what actually landed
on `{{feature_branch}}`:

- Did the merged code implement the ticket's described behaviour?
- Were any requirements silently dropped during conflict resolution?
- Did the merge accidentally revert or weaken an earlier ticket's work?

Use `git log --oneline {{feature_branch}}` and `git diff` to inspect the
actual changes.

## Step 3 — Build and test

1. Run the full monorepo test suite (`mvnd test`).
2. Grep for stray conflict markers:
   `grep -rE '^(<<<<<<<|=======|>>>>>>>)' --include='*.java' --include='pom.xml'`.
3. Confirm the reactor still resolves: every module in the root `pom.xml`
   `<modules>` list exists, and `PROJECTS.md` matches the module inventory.

## Step 4 — Look for duplicated effort across tickets

Multiple tickets running in parallel often add overlapping code. Look for:

- Two tickets that introduced near-identical records, interfaces, helpers,
  or constants.
- A ticket that reimplemented something another ticket (or an existing
  `libs/*` module) already provides.
- Repeated patterns that should be consolidated into one helper.

If you spot a duplicate that's small and obvious, fix it now. If it's
larger, file a follow-up ticket (see Step 6).

## Step 5 — Fix immediate problems

Fix in-place and commit:
- Stray conflict markers
- Compilation errors and broken tests caused by the merge
- Missing imports, mismatched signatures, dropped symbols
- Small duplications that are trivial to consolidate

## Step 6 — File follow-up tickets *only when necessary*

**The target is zero new tickets.** Do not file tickets to demonstrate
diligence, to capture nice-to-haves, or to pad the backlog. Most iterations
should end with no tickets created.

**You must file a ticket** when one of these is true and the fix is too
large to do here:

- A test in the full suite is failing because of the merge and the fix is
  non-trivial (more than a few lines or touches code outside this iteration's
  scope).
- A merged ticket's actual changes don't match what its `st show` description
  asked for — wrong behaviour, missing requirement, or a regression that
  needs deliberate work to repair.
- A real structural problem that the merge surfaced or worsened: a module
  missing from the root `pom.xml` or `PROJECTS.md`, a `libs/*` module that
  gained I/O, wall-clock, or random calls (purity/determinism break), a
  forbidden dependency (Spring, Kotlin, Lombok, Hibernate) creeping in, or
  significant code duplication that needs consolidation.

**Do not file** tickets for: style nitpicks, things you already fixed,
hypothetical future work, or issues that are not actually broken right now.

When you do file one, be specific, and **always include the epic label**
(`tech-wilds` or `tech-auth`) plus a topic label — tickets without their
epic label never reach the epic's node:

```bash
st add --project KOTHARWORKS \
  --labels tech-wilds,<topic> \
  --summary "Short, specific summary" \
  --description "What's wrong, where (file/class), why it matters, and what 'done' looks like."
```

If the issue clearly blocks something specific, set a dependency with
`--depends-on`.

## Step 7 — Commit and push

If you changed anything, commit on `{{feature_branch}}` and push. Mention
any tickets you filed in the commit message.
