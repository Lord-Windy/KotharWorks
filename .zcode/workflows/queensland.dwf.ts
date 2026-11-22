/* zcode-workflow
description: "Queensland-style ticket line: takes ready KOTHARWORKS tickets one
  at a time, gives each a git worktree and ql/<key> branch, has GLM-5.3 plan and
  review with GLM-5.3-Flash implementing, gates on the full mvnd test reactor
  (Java 25, -Werror) with one repair round per gate, then fast-forward merges to
  main, pushes to origin (GitHub) and the SourceHut mirror
  (git@git.sr.ht:~lord_windy/KotharWorks), marks the ticket done, and promotes
  backlog dependents whose dependencies have all landed. Stops the line on
  failure, leaving the worktree for inspection."
whenToUse: When you want to process the current KotharWorks ticket queue (the
  Phase 0 spine under tickets/, or any ready samtasker (st) tickets)
  Queensland-fashion — per-ticket git worktrees, ql/<key> branches, sequential
  ff-merges to main with push to both remotes (origin/GitHub + SourceHut), st
  done and dependent promotion.
args:
  label:
    type: string
    description: Only process ready KOTHARWORKS tickets carrying this label; empty
      means all labels (the Phase 0 chain mixes tech-wilds and tech-auth, and
      filtering to one epic stalls cross-epic dependencies).
    default: ""
  limit:
    type: number
    description: Maximum tickets to process in one run (clamped to 1-12).
    default: 12
*/
// Queensland, ZCode edition: a sequential ticket line over samtasker.
// Per ticket: fresh git worktree + ql/<key> branch, GLM-5.3 plans and reviews,
// GLM-5.3-Flash implements, a deterministic mvnd test gate with one repair
// round, a merge review with one repair round, then ff-merge to main, push,
// st done, and promotion of newly-unblocked backlog dependents. A gate that
// stays red stops the line and leaves the worktree.

interface TicketJson {
  key: string;
  summary: string;
  description: string;
  labels: string[];
  status: string;
  dependencies: string[];
  dependents: string[];
  created_at: string;
}

interface Plan {
  /** Files the implementation must touch or create, repo-relative. */
  files: string[];
  /** Step-by-step instructions the implementer can follow without re-deriving anything. */
  approach: string;
  /** Ambiguities, traps, or acceptance criteria the gates cannot check; empty when none. */
  risks: string[];
}

interface Implementation {
  /** One paragraph: what was actually done. */
  summary: string;
  /** One-line commit message in the repo's existing style. */
  commitMessage: string;
  /** Anything intentionally left undone; empty when complete. */
  leftovers: string;
}

interface ReviewFinding {
  /** File, and line when it applies, the problem is in. */
  where: string;
  /** One sentence: what is wrong. */
  problem: string;
  /** high only for data loss, crashes or wrong results. */
  severity: "low" | "medium" | "high";
}

interface Review {
  /** True only when you would merge this diff yourself. */
  approved: boolean;
  /** Every problem that should block the merge; empty when approving. */
  findings: ReviewFinding[];
}

interface TicketOutcome {
  /** Ticket key, e.g. 4e263046. */
  key: string;
  /** Ticket summary, trimmed for card display. */
  summary: string;
  /** Where the ticket is on the line. */
  status: "planning" | "implementing" | "gating" | "reviewing" | "merged" | "failed";
  /** Short status detail: merge sha, or why it failed. */
  note: string;
}

interface Finding {
  /** Workspace-relative path, with a line when it applies. */
  where: string;
  /** One sentence: what is wrong, or what was found. */
  what: string;
  /** What showed it: the lines read, or the command and output that proved it. */
  evidence: string;
  /** "verified" when a deterministic check confirmed it; "unconfirmed" when reviewer judgement only. */
  status: "verified" | "unconfirmed";
  /** How much it matters. Reserve "high" for data loss, a crash, or a wrong result. */
  severity: "low" | "medium" | "high";
}

interface WorkflowReport {
  /** Two or three sentences answering what the user asked for. */
  conclusion: string;
  findings: Finding[];
  /** What the run checked and how: the commands it ran, the files it covered. */
  verified: string[];
  /** What the run did not look at or could not check, and why. */
  notCovered: string[];
}

const REPO = "/home/sam/dev/kotharworks";
const MODEL_JUDGE = "account:zai-individual-coding-plan/GLM-5.3";
const MODEL_WRITE = "account:zai-individual-coding-plan/GLM-5.3-Flash";

const PLANNER_PERSONA = `You are the planning half of an automated ticket line in the KotharWorks monorepo (/home/sam/dev/kotharworks — pure Java 25 LTS, Apache Maven via mvnd, conventions in AGENTS.md). A faster implementer executes your plan verbatim in a fresh worktree, so the plan must be complete and file-level precise: exact files to create or edit, what goes in each, the Maven wiring for new modules (root pom.xml <modules> and <dependencyManagement>, plus PROJECTS.md registration), and how the acceptance criteria will be checked by the full mvnd test reactor. Ticket descriptions reference spec files under tickets/ and planning docs under planning/ — read the spec and the actual sources before planning; never plan from the ticket text alone. You edit nothing. If the ticket is ambiguous, internally inconsistent, or its acceptance criteria cannot be met as written, say so in risks rather than inventing requirements. If the ticket cannot be planned honestly, escalate and say so.`;

const IMPLEMENTER_PERSONA = `You are the implementing half of an automated ticket line in the KotharWorks monorepo. You write production Java 25 inside one dedicated git worktree, following the plan you are handed. Read AGENTS.md in the worktree before writing code and follow it: pure Java only (no Kotlin, Lombok, Spring Boot, Hibernate/JPA or annotation processors — warnings are errors); immutable records for commands, events, value IDs and DTOs, sealed interfaces with exhaustive switches for closed families, small @FunctionalInterface strategy seams; flat entity tables addressed by typed value IDs inside simulation state, never mutable object references across entities; libs/* stay 100% pure and deterministic (no wall-clock calls, no Math.random/ThreadLocalRandom, no file/network/SQL I/O — logical time and seeded RNGs are passed in); Virtual Threads with plain synchronous code in apps/*, never CompletableFuture chains or reactive streams; hexagonal layering (domain/ports/adapters/commands) only in apps/*; Apache-2.0 license header (copyright Samuel "Lord-Windy" Brown, year the file is created) on every new .java and pom.xml; new modules registered in the root pom.xml and PROJECTS.md. The orchestrator gates your work on the full mvnd test reactor and a reviewer then reads your diff: write so both pass honestly. If a gate cannot legitimately be passed, or the plan you were handed is wrong, escalate and say so plainly — never fake, weaken or work around a check. You never run git commit, branch, push or checkout, never touch anything outside your worktree, and never run st.`;

const REVIEWER_PERSONA = `You are the merge gate of an automated ticket line in the KotharWorks monorepo: a diff you approve fast-forwards into main and is pushed. You read diffs and their surrounding code and judge whether you would merge them yourself; you edit nothing. The full mvnd test reactor has already passed — do not re-run it; judge what it cannot see: whether the diff actually does what the ticket's acceptance criteria say (read the spec file under tickets/ that the description references), whether it drifts from AGENTS.md conventions (license headers, pure Java, records/sealed types, flat entity tables with typed IDs, purity and determinism of libs/*, Virtual Threads over reactive, hexagonal layering only in apps/*, module registration in the root pom.xml and PROJECTS.md), and whether it could break existing consumers. Approving takes evidence, objecting is the safe default, and you do not invent nits to look thorough. Reserve "high" severity for data loss, crashes or wrong results. If the ticket as written cannot be satisfied by any honest diff, say that in a finding instead of approving something wrong.`;

const label = typeof args.label === "string" ? args.label : "";
const limitRaw = typeof args.limit === "number" ? args.limit : 12;
const limit = Math.min(12, Math.max(1, Math.floor(limitRaw)));
const labelText = label === "" ? "all labels" : `'${label}'`;

artifact.board("queue", {
  title: "Ticket line",
  key: "key",
  status: "status",
  columns: ["planning", "implementing", "gating", "reviewing", "merged", "failed"],
  cardTitle: "key",
  detail: [{ field: "summary" }, { field: "note" }],
});

async function runGates(wt: string): Promise<{ pass: boolean; failures: string }> {
  const test = await world.run("/home/sam/.sdkman/candidates/mvnd/current/bin/mvnd", ["-f", `${wt}/pom.xml`, "test"], { timeoutMs: 1_800_000, output: { tail: 131072 } });
  if (test.exitCode === 0) return { pass: true, failures: "" };
  return { pass: false, failures: `mvnd test failed (exit ${test.exitCode}):\n${test.stdout}\n${test.stderr}`.slice(0, 20000) };
}

async function promoteDependents(): Promise<{ promoted: string[]; error: string | null }> {
  const all = await world.run("st", ["list", "--project", "KOTHARWORKS", "--json"], { timeoutMs: 60_000 });
  if (all.exitCode !== 0) return { promoted: [], error: `st list exited ${all.exitCode}` };
  let tickets: TicketJson[] = [];
  try {
    tickets = JSON.parse(all.stdout) as TicketJson[];
  } catch {
    return { promoted: [], error: "st list printed output that could not be parsed as JSON" };
  }
  const settled = new Set(tickets.filter(t => t.status === "done" || t.status === "cancelled").map(t => t.key));
  const promotable = tickets.filter(t => t.status === "backlog" && t.dependencies.every(d => settled.has(d)));
  const promoted: string[] = [];
  for (const t of promotable) {
    const r = await world.run("st", ["status", t.key, "ready"], { timeoutMs: 60_000 });
    if (r.exitCode !== 0) return { promoted, error: `st status ${t.key} ready failed (exit ${r.exitCode})` };
    promoted.push(t.key);
  }
  return { promoted, error: null };
}

function reviewAsk(wt: string, branch: string, key: string, summary: string, description: string, secondRound: boolean): string {
  return `Review ticket ${key} (${summary}) before it fast-forward merges into main and is pushed.\n\n` +
    `Worktree: ${wt}, branch ${branch}. The implementer's work sits UNCOMMITTED in the working tree — that is by design: the orchestrator commits only after your approval, so an empty commit list on the branch is NOT a finding. See the change with:\n` +
    `  git -C ${wt} status --porcelain\n  git -C ${wt} diff main\n` +
    `Untracked files ('??' in status) are part of the change — read them in full.\n` +
    `Read whatever surrounding code and docs you need (AGENTS.md, the ticket's spec under tickets/, planning docs under planning/) — but edit nothing.\n\n` +
    `Judge the diff against:\n` +
    `1. the ticket's acceptance criteria:\n${description}\n` +
    `2. house conventions from AGENTS.md;\n` +
    `3. whether it could break existing consumers.\n\n` +
    `The full mvnd test reactor (compile with -Werror plus every module's test suite) already ran green in this worktree — do not re-run it; judge the diff itself.` +
    (secondRound ? `\n\nA first review raised findings that have since been addressed; judge the diff as it stands now.` : "");
}

function trim(s: string, n: number): string {
  return s.length > n ? `${s.slice(0, n - 3)}...` : s;
}

phase("Check the repo, the toolchain and the ticket queue");
const repoStatus = await git.status();
if (repoStatus.branch !== "main" || !repoStatus.clean) {
  return {
    conclusion: `Stopped before touching anything: the main checkout is on ${repoStatus.branch ?? "a detached head"} and ${repoStatus.clean ? "clean" : "dirty"}. The line needs a clean main to fast-forward onto.`,
    findings: [] as Finding[],
    verified: [] as string[],
    notCovered: ["nothing — the run stopped in preflight"],
  };
}
const stCheck = await world.run("st", ["list", "--ready", "--project", "KOTHARWORKS", "--json"], { timeoutMs: 60_000 });
if (stCheck.exitCode !== 0) {
  return {
    conclusion: `Stopped before touching anything: samtasker is unreachable (st exited ${stCheck.exitCode}) — is wg0 up?`,
    findings: [] as Finding[],
    verified: [] as string[],
    notCovered: ["nothing — the run stopped in preflight"],
  };
}
const mvndCheck = await world.run("/home/sam/.sdkman/candidates/mvnd/current/bin/mvnd", ["--version"], { timeoutMs: 120_000 });
if (mvndCheck.exitCode !== 0) {
  return {
    conclusion: `Stopped before touching anything: mvnd is not runnable at /home/sam/.sdkman/candidates/mvnd/current/bin/mvnd (exit ${mvndCheck.exitCode}).`,
    findings: [] as Finding[],
    verified: [] as string[],
    notCovered: ["nothing — the run stopped in preflight"],
  };
}
const remotes = (await world.run("git", ["-C", REPO, "remote"], { timeoutMs: 30_000 })).stdout.split("\n").map(s => s.trim()).filter(s => s !== "");
if (!remotes.includes("origin")) {
  return {
    conclusion: `Stopped before touching anything: no 'origin' remote, so per-ticket pushes would fail. Remotes found: ${remotes.join(", ") || "none"}.`,
    findings: [] as Finding[],
    verified: [] as string[],
    notCovered: ["nothing — the run stopped in preflight"],
  };
}
const ahead = await world.run("git", ["-C", REPO, "rev-list", "--count", "origin/main..main"], { timeoutMs: 30_000 });
if (ahead.exitCode === 0 && Number(ahead.stdout.trim()) > 0) {
  log(`Note: local main is ${ahead.stdout.trim()} commit(s) ahead of origin/main; the first per-ticket push will carry them.`);
}
log(`Line opens: up to ${limit} ticket(s), ${labelText}.`);

const outcomes: TicketOutcome[] = [];
const findings: Finding[] = [];
const verified: string[] = [];
let mergedCount = 0;
let stopReason: string | null = null;

for (let n = 0; n < limit && stopReason === null; n += 1) {
  phase("Pick up the next ready ticket");
  const q = await world.run("st", ["list", "--ready", "--project", "KOTHARWORKS", "--json"], { timeoutMs: 60_000 });
  if (q.exitCode !== 0) {
    stopReason = `samtasker became unreachable mid-run (st exited ${q.exitCode}) — is wg0 still up? Everything merged so far is already pushed.`;
    break;
  }
  let readyAll: TicketJson[] = [];
  try {
    readyAll = JSON.parse(q.stdout) as TicketJson[];
  } catch {
    stopReason = "st list printed output that could not be parsed as JSON.";
    break;
  }
  const candidates = readyAll.filter(t => label === "" || t.labels.includes(label)).sort((a, b) => a.created_at.localeCompare(b.created_at));
  if (candidates.length === 0) {
    const refill = await promoteDependents();
    if (refill.error !== null) {
      stopReason = `Queue empty and dependent promotion failed: ${refill.error}. Promote the blocked backlog tickets by hand (st status <key> ready). Everything merged so far is already pushed.`;
      break;
    }
    if (refill.promoted.length === 0) {
      log(`No more ready tickets (${labelText}) — queue drained.`);
      break;
    }
    log(`Promoted ${refill.promoted.join(", ")} from backlog to ready; continuing the chain.`);
    continue;
  }
  const ticket = candidates[0];
  const key = ticket.key;
  const summary = ticket.summary;
  const shortSummary = trim(summary, 90);
  const wt = `${REPO}/worktrees/${key}`;
  const branch = `ql/${key}`;
  log(`Taking ticket ${key}: ${summary}`);

  const claim = await world.run("st", ["status", key, "in-progress"], { timeoutMs: 60_000 });
  if (claim.exitCode !== 0) {
    stopReason = `st status ${key} in-progress failed (exit ${claim.exitCode}).`;
    break;
  }
  const wtAdd = await world.run("git", ["-C", REPO, "worktree", "add", wt, "-b", branch], { timeoutMs: 120_000 });
  if (wtAdd.exitCode !== 0) {
    stopReason = `git worktree add failed for ${key}: ${trim(wtAdd.stderr, 400)}. If ${wt} is left over from an earlier attempt, remove it (git worktree remove) and reset the ticket before re-running. Ticket ${key} left in-progress.`;
    findings.push({ where: wt, what: "worktree could not be created", evidence: wtAdd.stderr, status: "verified", severity: "medium" });
    break;
  }
  report({ key, summary: shortSummary, status: "planning", note: "" }, "queue");

  phase("Plan the implementation");
  const planner = agent(`Planner (${key})`, { model: MODEL_JUDGE, system: PLANNER_PERSONA });
  const plan = await planner.ask<Plan>(
    `Ticket ${key}: ${summary}\n\nFull ticket description:\n${ticket.description}\n\n` +
    `The implementation happens in a fresh worktree of ${REPO} branched from main. ` +
    `Read the ticket's referenced spec files under ${REPO}/tickets/ and the sources it touches under ${REPO}, then produce the plan.`,
  );
  log(`Plan for ${key}: ${plan.files.length} file(s) to touch.`);

  phase("Implement in the worktree");
  report({ key, summary: shortSummary, status: "implementing", note: "" }, "queue");
  const implementer = agent(`Implementer (${key})`, { model: MODEL_WRITE, system: IMPLEMENTER_PERSONA });
  let impl = await implementer.ask<Implementation>(
    `Implement ticket ${key} in the git worktree ${wt} (branch ${branch}, freshly cut from main).\n\n` +
    `Ticket ${key}: ${summary}\n${ticket.description}\n\n` +
    `The planner's plan (follow it; deviate only with a stated reason):\n` +
    `Files: ${plan.files.join(", ") || "planner named none"}\n` +
    `Approach:\n${plan.approach}\n` +
    `Risks: ${plan.risks.join(" | ") || "none"}\n\n` +
    `Your shell starts in the main checkout, so run every Maven command against your worktree as: /home/sam/.sdkman/candidates/mvnd/current/bin/mvnd -f ${wt}/pom.xml <goals> ` +
    `(e.g. ... -pl libs/wilds -am test to iterate on one module). The orchestrator runs the full reactor (mvnd -f ${wt}/pom.xml test) afterwards. Return what you did and the commit message when finished.`,
  );
  const ticketCommitMessage = impl.commitMessage.trim() !== "" ? impl.commitMessage.trim() : summary;
  const repairNotes: string[] = [];

  phase("Gate on the full mvnd test reactor");
  report({ key, summary: shortSummary, status: "gating", note: "" }, "queue");
  let gates = await runGates(wt);
  if (!gates.pass) {
    phase("One repair round on the failing gates");
    log(`${key}: gates red — one repair round.`);
    impl = await implementer.ask<Implementation>(
      `The gates just failed on your work in ${wt}. Fix the causes in the worktree (same rules: no git commands, no st; you may run targeted mvnd builds/tests to iterate).\n\n${gates.failures}`,
    );
    if (impl.commitMessage.trim() !== "") repairNotes.push(impl.commitMessage.trim());
    phase("Re-run the gates after the repair");
    gates = await runGates(wt);
    if (!gates.pass) {
      stopReason = `${key}: gates still red after one repair round. Ticket left in-progress; the attempt is in ${wt} (branch ${branch}).`;
      findings.push({ where: wt, what: "mvnd test reactor still failing after one repair round", evidence: trim(gates.failures, 2000), status: "verified", severity: "high" });
      outcomes.push({ key, summary: shortSummary, status: "failed", note: "gates red after repair" });
      report({ key, summary: shortSummary, status: "failed", note: "gates red after repair" }, "queue");
      break;
    }
  }

  phase("Review the diff before merging");
  report({ key, summary: shortSummary, status: "reviewing", note: "" }, "queue");
  const reviewer = agent(`Reviewer (${key})`, { model: MODEL_JUDGE, system: REVIEWER_PERSONA });
  const firstReview = await reviewer.ask<Review>(reviewAsk(wt, branch, key, summary, ticket.description, false));
  if (!firstReview.approved) {
    phase("Repair the review findings");
    log(`${key}: first review not approved (${firstReview.findings.length} finding(s)) — repair round.`);
    impl = await implementer.ask<Implementation>(
      `The merge reviewer did not approve your work in ${wt}. Address every finding; same rules as before.\n\nFindings:\n${JSON.stringify(firstReview.findings, null, 2)}`,
    );
    if (impl.commitMessage.trim() !== "") repairNotes.push(impl.commitMessage.trim());
    phase("Re-gate and re-review with fresh eyes");
    gates = await runGates(wt);
    if (!gates.pass) {
      stopReason = `${key}: gates went red again after the review repairs. Ticket left in-progress; the attempt is in ${wt} (branch ${branch}).`;
      findings.push({ where: wt, what: "gates failing after review repairs", evidence: trim(gates.failures, 2000), status: "verified", severity: "high" });
      outcomes.push({ key, summary: shortSummary, status: "failed", note: "gates red after review repairs" });
      report({ key, summary: shortSummary, status: "failed", note: "gates red after review repairs" }, "queue");
      break;
    }
    const secondReviewer = agent(`Second reviewer (${key})`, { model: MODEL_JUDGE, system: REVIEWER_PERSONA });
    const secondReview = await secondReviewer.ask<Review>(reviewAsk(wt, branch, key, summary, ticket.description, true));
    if (!secondReview.approved) {
      stopReason = `${key}: a fresh second review still did not approve. Ticket left in-progress; the attempt is in ${wt} (branch ${branch}).`;
      for (const f of secondReview.findings) {
        findings.push({ where: f.where, what: f.problem, evidence: `finding of the independent second reviewer (GLM-5.3) on the ql/${key} diff`, status: "unconfirmed", severity: f.severity });
      }
      outcomes.push({ key, summary: shortSummary, status: "failed", note: "review not approved" });
      report({ key, summary: shortSummary, status: "failed", note: "review not approved" }, "queue");
      break;
    }
  }

  phase("Commit, fast-forward main and push");
  const porcelain = await world.run("git", ["-C", wt, "status", "--porcelain"], { timeoutMs: 60_000 });
  const aheadOfMain = await world.run("git", ["-C", wt, "rev-list", "--count", `main..${branch}`], { timeoutMs: 60_000 });
  const hasUncommitted = porcelain.stdout.trim() !== "";
  const hasCommits = aheadOfMain.exitCode === 0 && aheadOfMain.stdout.trim() !== "0";
  if (!hasUncommitted && !hasCommits) {
    stopReason = `${key}: the implementation produced no changes in the worktree. Ticket left in-progress; worktree at ${wt}. Implementer reported: ${trim(impl.summary, 300)}`;
    outcomes.push({ key, summary: shortSummary, status: "failed", note: "no changes produced" });
    report({ key, summary: shortSummary, status: "failed", note: "no changes produced" }, "queue");
    break;
  }
  if (hasUncommitted) {
    await world.run("git", ["-C", wt, "add", "-A"], { timeoutMs: 120_000 });
    const commitMsg = repairNotes.length === 0
      ? ticketCommitMessage
      : `${ticketCommitMessage}\n\nRepairs folded in before merge:\n${repairNotes.map(m => `- ${m}`).join("\n")}`;
    const commit = await world.run("git", ["-C", wt, "commit", "-m", commitMsg], { timeoutMs: 120_000 });
    if (commit.exitCode !== 0) {
      stopReason = `${key}: git commit failed in the worktree: ${trim(commit.stderr, 400)}. Ticket left in-progress; worktree at ${wt}.`;
      outcomes.push({ key, summary: shortSummary, status: "failed", note: "commit failed" });
      report({ key, summary: shortSummary, status: "failed", note: "commit failed" }, "queue");
      break;
    }
  }
  const ff = await world.run("git", ["-C", REPO, "merge", "--ff-only", branch], { timeoutMs: 120_000 });
  if (ff.exitCode !== 0) {
    stopReason = `${key}: fast-forward merge into main failed (main moved?): ${trim(ff.stderr, 400)}. Ticket left in-progress; worktree at ${wt}.`;
    outcomes.push({ key, summary: shortSummary, status: "failed", note: "ff-merge failed" });
    report({ key, summary: shortSummary, status: "failed", note: "ff-merge failed" }, "queue");
    break;
  }
  const push = await world.run("git", ["-C", REPO, "push", "origin", "main"], { timeoutMs: 300_000 });
  if (push.exitCode !== 0) {
    stopReason = `${key}: merged into local main but the push failed: ${trim(push.stderr, 400)}. Push both remotes manually (origin reaches GitHub only; SourceHut is git@git.sr.ht:~lord_windy/KotharWorks), then run: st status ${key} done`;
    findings.push({ where: REPO, what: "push to origin failed after a local ff-merge", evidence: push.stderr, status: "verified", severity: "high" });
    outcomes.push({ key, summary: shortSummary, status: "failed", note: "push failed — push manually then st done" });
    report({ key, summary: shortSummary, status: "failed", note: "push failed" }, "queue");
    break;
  }
  const pushSrht = await world.run("git", ["-C", REPO, "push", "git@git.sr.ht:~lord_windy/KotharWorks", "main"], { timeoutMs: 300_000 });
  if (pushSrht.exitCode !== 0) {
    log(`${key}: WARNING — origin push ok, but the SourceHut mirror push failed; SourceHut is behind until pushed by hand: ${trim(pushSrht.stderr, 200)}`);
    findings.push({ where: REPO, what: `SourceHut mirror push failed after merging ${key} (origin/GitHub is current)`, evidence: pushSrht.stderr, status: "verified", severity: "medium" });
  }
  const head = await world.run("git", ["-C", REPO, "rev-parse", "--short", "HEAD"], { timeoutMs: 30_000 });
  const sha = head.stdout.trim();
  const done = await world.run("st", ["status", key, "done"], { timeoutMs: 60_000 });
  if (done.exitCode !== 0) {
    stopReason = `${key}: merged as ${sha} and pushed, but 'st status ${key} done' failed — run it by hand so dependents promote.`;
    outcomes.push({ key, summary: shortSummary, status: "merged", note: `${sha} — run st status ${key} done by hand` });
    report({ key, summary: shortSummary, status: "merged", note: "st done failed — run by hand" }, "queue");
    break;
  }
  const promo = await promoteDependents();
  if (promo.error !== null) {
    stopReason = `${key}: merged as ${sha} and pushed, but promoting its dependents failed: ${promo.error}. Promote them by hand (st status <key> ready).`;
    outcomes.push({ key, summary: shortSummary, status: "merged", note: `${sha} — promote dependents by hand` });
    report({ key, summary: shortSummary, status: "merged", note: "promotion failed — by hand" }, "queue");
    break;
  }
  await world.run("git", ["-C", REPO, "worktree", "remove", wt], { timeoutMs: 120_000 });
  await world.run("git", ["-C", REPO, "branch", "-d", branch], { timeoutMs: 60_000 });
  mergedCount += 1;
  verified.push(`${key}: full mvnd test reactor green in a fresh worktree, diff reviewed on GLM-5.3${firstReview.approved ? "" : " (approved on second review after repairs)"}, ff-merged to main as ${sha} and pushed${pushSrht.exitCode === 0 ? " to both remotes (origin/GitHub + SourceHut)" : " to origin/GitHub (SourceHut push failed — see findings)"}.`);
  outcomes.push({ key, summary: shortSummary, status: "merged", note: sha });
  report({ key, summary: shortSummary, status: "merged", note: sha }, "queue");
  log(`${key}: merged as ${sha}, pushed, ticket done${promo.promoted.length > 0 ? `, promoted ${promo.promoted.join(", ")}` : ""}.`);
}

phase("Publish the run ledger");
const logRes = await world.run("git", ["-C", REPO, "log", "-n", String(Math.min(30, mergedCount + 2)), "--pretty=format:%h %s"], { timeoutMs: 30_000 });
const recent = logRes.exitCode === 0 ? logRes.stdout.trim().split("\n").filter(l => l !== "") : [];
const lines: string[] = [
  `# Queensland run — ${labelText} queue`,
  ``,
  stopReason === null ? `Queue drained: ${mergedCount} ticket(s) merged to main and pushed.` : `**Line stopped** after ${mergedCount} merge(s).`,
  ``,
  `| Ticket | Outcome | Note |`,
  `| --- | --- | --- |`,
  ...outcomes.map(o => `| ${o.key} | ${o.status} | ${o.note} |`),
];
if (stopReason !== null) {
  lines.push(``, `Stop reason: ${stopReason}`);
}
lines.push(``, `Recent main:`, ...recent.map(l => `- ${l}`));
await artifact.markdown("ledger", lines.join("\n"), {
  title: "Queensland run ledger",
  description: `Per-ticket outcomes for the ${labelText} queue: ${mergedCount} merged${stopReason === null ? ", queue drained" : ", line stopped"}.`,
  primary: true,
});

return {
  conclusion: stopReason === null
    ? `Drained the ${labelText} queue: ${mergedCount} ticket(s) implemented on Flash, planned and reviewed on GLM-5.3, gated on the mvnd test reactor, ff-merged to main and pushed.`
    : `Line stopped after ${mergedCount} merge(s): ${stopReason}`,
  findings,
  verified,
  notCovered: [
    "acceptance criteria that need a human at the keyboard (running apps/wilds-server via exec:java and browsing/curling it, Postgres-backed flows) — the gate covers the mvnd test reactor only",
    "reviewers judge the diff and do not re-run the test reactor (already gated)",
  ],
};
