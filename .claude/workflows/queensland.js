export const meta = {
  name: 'queensland',
  description: 'Queensland ticket line with a fleet: an Opus coordinator splits each ready KOTHARWORKS ticket into waves of Haiku/Sonnet/Opus subtasks, gates on mvnd (+systest), runs prompts/review.md and prompts/deep-review.md, fixes confirmed findings, then ff-merges to main, pushes, marks st done and promotes dependents',
  whenToUse: 'Work through the ready KotharWorks samtasker (st) queue one ticket at a time, Queensland-style (per-ticket worktree + ql/<key> branch, sequential ff-merge to main), with multi-agent implementation and the repo review passes. Args: label, limit, tickets, push, systest, fixDeep, repairRounds, checkRounds, dryRun, check.',
  phases: [
    { title: 'Preflight', detail: 'repo, st, mvnd, docker, remotes', model: 'haiku' },
    { title: 'Pick', detail: 'next ready ticket, claim it, cut its worktree', model: 'haiku' },
    { title: 'Plan', detail: 'coordinator splits the ticket into waves and picks a model per subtask', model: 'opus' },
    { title: 'Build', detail: 'fleet implements wave by wave; parallel subtasks get their own worktrees' },
    { title: 'Gate', detail: 'mvnd test reactor (and ./systest.sh at the final gate), coordinator-led repairs' },
    { title: 'Review', detail: 'prompts/review.md pipeline review, fix mode', model: 'opus' },
    { title: 'Deep review', detail: 'prompts/deep-review.md report-only, then skeptics try to refute each HIGH/MEDIUM' },
    { title: 'Fix', detail: 'coordinator assigns fixes for confirmed findings, each with a regression test' },
    { title: 'Merge', detail: 'squash commit, move spec to tickets/done, ff-merge main, push, st done, promote dependents', model: 'sonnet' },
  ],
}

// ---------------------------------------------------------------------------
// Args (all optional):
//   label        only ready tickets carrying this st label ('' = all)
//   limit        max tickets this run (1-12, default 12)
//   tickets      explicit st keys to process in order (overrides the queue)
//   push         push main to origin + SourceHut after each merge (default true)
//   systest      'auto' (coordinator decides) | 'always' | 'never' — ./systest.sh at the final gate
//   fixDeep      which confirmed deep-review findings the line fixes: 'high' (default, per AGENTS.md)
//                | 'high-medium' | 'none'
//   repairRounds coordinator repair rounds per red gate (0-4, default 2)
//   checkRounds  coordinator completeness checks after each batch of waves (0-4, default 2)
//   dryRun       preflight + pick + plan one ticket, change nothing
//   check        return the resolved config without spawning any agent
// ---------------------------------------------------------------------------

const REPO = '/home/sam/dev/kotharworks'
const MVND = '/home/sam/.sdkman/candidates/mvnd/current/bin/mvnd'
const SRHT = 'git@git.sr.ht:~lord_windy/KotharWorks'
const REPORTS = `${REPO}/worktrees/_reports`
const MODELS = ['haiku', 'sonnet', 'opus'] // the fleet never runs on Fable
const EFFORTS = ['low', 'medium', 'high', 'xhigh']
const TRAILER = 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>'
const MAX_WAVES = 6
const MAX_PER_WAVE = 5

const A = args && typeof args === 'object' ? args : {}
const clampInt = (v, lo, hi, d) => {
  const n = Math.floor(Number(v))
  return Number.isFinite(n) ? Math.min(hi, Math.max(lo, n)) : d
}
const cfg = {
  label: typeof A.label === 'string' ? A.label.trim() : '',
  tickets: Array.isArray(A.tickets) ? A.tickets.map(k => String(k).trim()).filter(Boolean) : [],
  limit: clampInt(A.limit, 1, 12, 12),
  push: A.push !== false,
  systest: ['auto', 'always', 'never'].includes(A.systest) ? A.systest : 'auto',
  fixDeep: ['high', 'high-medium', 'none'].includes(A.fixDeep) ? A.fixDeep : 'high',
  repairRounds: clampInt(A.repairRounds, 0, 4, 2),
  checkRounds: clampInt(A.checkRounds, 0, 4, 2),
  dryRun: A.dryRun === true,
}
if (cfg.tickets.length) cfg.limit = cfg.tickets.length
if (cfg.dryRun && !cfg.tickets.length) cfg.limit = 1
if (A.check === true) return { config: cfg }

// ---------------------------------------------------------------------------
// Schemas
// ---------------------------------------------------------------------------

const str = { type: 'string' }
const bool = { type: 'boolean' }
const strs = { type: 'array', items: str }
const obj = (properties, required) => ({ type: 'object', properties, required: required || Object.keys(properties) })

const OPS = obj({ ok: bool, detail: str })
const PREFLIGHT = obj({ ok: bool, problems: strs, warnings: strs })
const PICK = obj({ found: bool, key: str, summary: str, spec: str, promoted: strs, error: str })
const SUBTASK = obj({
  id: str,
  title: str,
  model: { type: 'string', enum: MODELS },
  effort: { type: 'string', enum: EFFORTS },
  files: strs,
  instructions: str,
  doneWhen: str,
  why: str,
}, ['id', 'title', 'model', 'effort', 'files', 'instructions', 'doneWhen'])
const COORD = obj({
  abort: str,
  complete: bool,
  summary: str,
  specFile: str,
  needsSystest: bool,
  commitMessage: str,
  waves: { type: 'array', items: obj({ subtasks: { type: 'array', items: SUBTASK } }) },
}, ['abort', 'complete', 'summary', 'waves'])
const SUBRESULT = obj({
  status: { type: 'string', enum: ['done', 'partial', 'blocked'] },
  summary: str,
  filesChanged: strs,
  verification: str,
  notes: str,
})
const INTEGRATE = obj({ ok: bool, compiled: bool, conflicts: strs, detail: str })
const GATE = obj({ pass: bool, failedStep: str, failures: str, logPath: str })
const REVIEW = obj({
  findings: { type: 'array', items: obj({ severity: { type: 'string', enum: ['minor', 'major'] }, where: str, problem: str, fixed: bool, howFixed: str }) },
  reviewFocus: { type: 'array', items: obj({ item: str, verdict: str, evidence: str }) },
  changedFiles: bool,
  mvndGreen: bool,
  notes: str,
})
const DEEP = obj({
  reportPath: str,
  summary: str,
  dependentsClear: bool,
  findings: { type: 'array', items: obj({ id: str, severity: { type: 'string', enum: ['HIGH', 'MEDIUM', 'LOW', 'QUESTION'] }, where: str, scenario: str, proof: str }) },
  reviewFocus: { type: 'array', items: obj({ item: str, verdict: str }) },
})
const VERDICT = obj({ verdict: { type: 'string', enum: ['confirmed', 'refuted', 'uncertain'] }, reasoning: str })
const MERGE = obj({
  ok: bool,
  failedStep: str,
  sha: str,
  pushedOrigin: bool,
  pushedSrht: bool,
  stDone: bool,
  promoted: strs,
  warnings: strs,
  detail: str,
})

// ---------------------------------------------------------------------------
// Shell snippets (python avoids backslashes so the JS template keeps them intact)
// ---------------------------------------------------------------------------

const PICK_SH = (label, skip) => `st list --ready --project KOTHARWORKS --json | python3 -c '
import json, sys
label, skip = sys.argv[1], set(filter(None, sys.argv[2].split(",")))
ts = [t for t in json.load(sys.stdin) if (not label or label in t.get("labels", [])) and t["key"] not in skip]
ts.sort(key=lambda t: t.get("created_at", ""))
if ts:
    t = ts[0]
    first = next(iter((t.get("description") or "").splitlines()), "")
    print(json.dumps({"key": t["key"], "summary": t["summary"], "spec": first}))
' '${label}' '${skip.join(',')}'`

const PROMOTE_SH = `for k in $(st list --project KOTHARWORKS --json | python3 -c '
import json, sys
ts = json.load(sys.stdin)
settled = {t["key"] for t in ts if t["status"] in ("done", "cancelled")}
print(" ".join(t["key"] for t in ts if t["status"] == "backlog" and all(d in settled for d in t.get("dependencies", []))))
'); do st status "$k" ready && echo "PROMOTED $k"; done`

// ---------------------------------------------------------------------------
// Personas and shared rules
// ---------------------------------------------------------------------------

const OPS_PERSONA = `You are the mechanical operator of an automated ticket line for the KotharWorks monorepo (${REPO}). Run exactly the commands you are given, in order, and report what happened truthfully in the requested structure. Do not improvise fixes, do not edit source files, do not delete anything you were not told to delete. When a step fails, stop and report which step and its stderr.`

const COORD_PERSONA = `You are the coordinator of an automated ticket line for the KotharWorks monorepo (${REPO}; pure Java 25, mvnd, conventions in AGENTS.md). You never edit files and never change git or st state. You read the ticket, its spec and the code, then plan work for a fleet of implementer agents and pick the model for each piece. Your plan is all an implementer sees besides the repo, so it must be concrete and self-contained.`

const SPLIT_RULES = `How to split work into waves and subtasks:
- Waves run in order; each wave starts from the merged result of all earlier waves. Subtasks inside one wave run at the same time in separate git worktrees and are merged afterwards, so subtasks in the same wave must not edit the same files: pom.xml files, PROJECTS.md, shared test fixtures, SQL migration numbering and package-level registries count. Put shared-file edits in a single subtask or an earlier wave.
- Only parallelise genuinely independent work. A small ticket is one wave with one subtask, and that is fine. Prefer a few substantial subtasks over many tiny ones: every subtask pays to read the codebase. Hard caps: ${MAX_WAVES} waves, ${MAX_PER_WAVE} subtasks per wave.
- Each subtask's instructions must stand alone: name exact files, types, signatures, behaviour, which tests to add and how to run them (${MVND} -f <worktree>/pom.xml -pl <module> -am test). Tests belong in the same subtask as the code they test. New gameplay/API behaviour gets a live systest test (systest/ conventions in systest/README.md); bug fixes get a regression test.
- Map every deliverable, acceptance criterion and Review Focus item in the spec to a subtask, quoting the item in that subtask's instructions.
- Give each subtask a short kebab-case id unique within the plan.
- Model per subtask, from exactly these three:
  - haiku: mechanical, fully specified edits: moving/renaming files, docs and index tables, license headers, registering a module in pom.xml/PROJECTS.md, boilerplate that follows an existing pattern you point at.
  - sonnet: the default for ordinary implementation plus tests against a clear spec.
  - opus: subtle or high-risk work: concurrency, Commit-Before-Mutate failure ordering, determinism / WorldHash / rules versioning, auth / CSRF / XSS / fog-of-war, SQL migrations and crash recovery, cross-module API design, anything you would worry about getting wrong.
  Effort: low for mechanical, medium by default, high for opus-grade risk, xhigh only for the single hardest piece. Say why in one line ("why").
- Do not plan "run the full build", "review" or "commit" subtasks: the line gates, reviews and commits by itself.
- If the ticket cannot be done honestly as written (contradictory spec, impossible acceptance criterion, missing dependency), set abort to the reason instead of planning around it.`

function workerRules(t, wt) {
  return `Rules:
- Work only inside ${wt}. Your shell starts in ${REPO}, the main checkout: never edit files there or in any other worktree. Use absolute paths under ${wt}.
- Standards: AGENTS.md and ${REPO}/prompts/implement.md (its non-negotiables and "Before you write code" section) apply. Ignore implement.md's "commit and push" step. Full ticket spec: \`st show ${t.key} --json\` (read only) or ${t.specFile ? `${wt}/${t.specFile}` : 'the spec file under tickets/'}.
- Treat any Review Focus item your work touches as a risk to cover with a test.
- Build and test with \`${MVND} -f ${wt}/pom.xml -pl <module> -am test\` (add -Dtest=... to iterate). Do not run ./systest.sh; the line runs it.
- No git commands that change state (add, commit, checkout, switch, branch, reset, stash, merge, rebase, push) and no st status changes. Read-only git (status, diff, log, show) is fine.
- Never weaken a test, gate or warning setting to get green (no @Disabled, -DskipTests, loosened -Xlint/-Werror, relaxed architecture gates, deleted assertions). If the work cannot be done honestly, or your instructions are wrong, stop and return status "blocked" saying why.`
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

class Stop extends Error {}
const stop = msg => { throw new Stop(msg) }
const pickModel = (m, fallback) => (MODELS.includes(m) ? m : fallback)
const slug = s => String(s || 'task').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 24) || 'task'
const trim = (s, n) => { s = String(s || ''); return s.length > n ? `${s.slice(0, n - 3)}...` : s }
const compact = v => JSON.stringify(v, null, 1)

function ops(prompt, schema, label, phaseName, model) {
  return agent(`${OPS_PERSONA}\n\n${prompt}`, { label, phase: phaseName, schema, model: model || 'haiku', effort: 'low' })
}

function coordinator(t, prompt, label, phaseName, effort) {
  return agent(`${COORD_PERSONA}\n\n${prompt}`, { label: `${t.key} ${label}`, phase: phaseName, schema: COORD, model: 'opus', effort: effort || 'high' })
}

function fleetDigest(t, last) {
  const rs = last ? t.reports.slice(-last) : t.reports
  if (!rs.length) return '(none yet)'
  return rs.map(r => `- ${r.tag}:${r.id} [${r.model}/${r.effort}] ${r.title} → ${r.status}: ${trim(r.summary, 400)}${r.notes ? ` | notes: ${trim(r.notes, 300)}` : ''}`).join('\n')
}

function normaliseWaves(t, waves, purpose) {
  const out = []
  const raw = Array.isArray(waves) ? waves : []
  if (raw.length > MAX_WAVES) log(`${t.key}: coordinator planned ${raw.length} waves for ${purpose}; running the first ${MAX_WAVES}, dropping ${raw.length - MAX_WAVES} (the completeness check will see what is missing).`)
  for (const w of raw.slice(0, MAX_WAVES)) {
    const subs = (w && Array.isArray(w.subtasks) ? w.subtasks : []).filter(s => s && s.instructions)
    if (!subs.length) continue
    if (subs.length > MAX_PER_WAVE) log(`${t.key}: a wave had ${subs.length} subtasks; running ${MAX_PER_WAVE}, dropping: ${subs.slice(MAX_PER_WAVE).map(s => s.id).join(', ')}.`)
    out.push(subs.slice(0, MAX_PER_WAVE))
  }
  return out
}

// ---------------------------------------------------------------------------
// Fleet execution
// ---------------------------------------------------------------------------

async function runSubtask(t, s, tag, wt, siblings, phaseName) {
  const model = pickModel(s.model, 'sonnet')
  const effort = EFFORTS.includes(s.effort) ? s.effort : 'medium'
  const sib = siblings.length
    ? `\nRunning at the same time in other worktrees (do not touch their files; they will be merged with yours):\n${siblings.map(o => `- ${o.id}: ${o.title} — files: ${(o.files || []).join(', ') || 'unspecified'}`).join('\n')}\n`
    : ''
  const prompt = `You are one implementer in a fleet working on KotharWorks ticket ${t.key} (${t.summary}). A coordinator split the ticket into subtasks; this one is yours. Do it completely, with its tests, and nothing beyond it.

Ticket context from the coordinator:
${t.planSummary}

Work already done by the fleet and present in your worktree:
${fleetDigest(t)}
${sib}
YOUR SUBTASK ${tag}:${s.id} — ${s.title}
Files you own: ${(s.files || []).join(', ') || 'see instructions'}
Instructions:
${s.instructions}
Done when: ${s.doneWhen}

Worktree: ${wt}

${workerRules(t, wt)}`
  const r = await agent(prompt, { label: `${t.key} ${tag}:${s.id} [${model}]`, phase: phaseName, schema: SUBRESULT, model, effort })
  const rep = {
    tag, id: s.id, title: s.title, model, effort,
    status: r ? r.status : 'blocked',
    summary: r ? r.summary : 'agent died or was skipped — nothing reported',
    notes: r ? r.notes : '',
  }
  t.reports.push(rep)
  t.dirty = true
  return rep
}

async function runWave(t, subs, phaseName) {
  t.waveNo += 1
  const tag = `w${t.waveNo}`
  if (subs.length === 1) {
    log(`${t.key} ${tag}: ${subs[0].id} on ${pickModel(subs[0].model, 'sonnet')}`)
    await runSubtask(t, subs[0], tag, t.wt, [], phaseName)
    return
  }
  const subs2 = subs.map(s => ({ s, wt: `${t.wt}--${tag}-${slug(s.id)}`, branch: `${t.branch}--${tag}-${slug(s.id)}` }))
  log(`${t.key} ${tag}: ${subs.length} in parallel — ${subs.map(s => `${s.id} [${pickModel(s.model, 'sonnet')}]`).join(', ')}`)
  const prep = await ops(`Prepare parallel wave ${tag} of ticket ${t.key}.
1. \`git -C ${t.wt} add -A\`, then if \`git -C ${t.wt} diff --cached --quiet\` exits non-zero: \`git -C ${t.wt} commit -q -m "wip(${t.key}): before ${tag}"\`.
2. For each line below run \`git -C ${REPO} worktree add <path> -b <branch> ${t.branch}\`. If a path or branch already exists, stop and report it (do not remove anything).
${subs2.map(x => `   path ${x.wt}  branch ${x.branch}`).join('\n')}
Return ok=true only if every worktree was created.`, OPS, `${t.key} ${tag} prep`, phaseName)
  if (!prep || !prep.ok) stop(`could not prepare parallel wave ${tag}: ${prep ? prep.detail : 'operator died'}`)

  await parallel(subs2.map(x => () => runSubtask(t, x.s, tag, x.wt, subs.filter(o => o !== x.s), phaseName)))

  const reps = t.reports.filter(r => r.tag === tag)
  const merged = await agent(`Integrate parallel wave ${tag} into ticket ${t.key}'s worktree ${t.wt} (branch ${t.branch}). Sub-worktrees, in merge order:
${subs2.map(x => `- ${x.wt} (branch ${x.branch}) — ${x.s.id}: ${x.s.title}`).join('\n')}

What each subtask reported:
${reps.map(r => `- ${r.id} (${r.status}): ${trim(r.summary, 500)}`).join('\n')}

For each sub-worktree in order:
 a. \`git -C <sub> add -A\`; if anything is staged, \`git -C <sub> commit -q -m "wip(${t.key}): ${tag} <id>"\`.
 b. \`git -C ${t.wt} merge --no-ff --no-edit <branch>\`. On conflicts, resolve them by editing the conflicted files following ${REPO}/prompts/resolve_conflict.md (combine independent changes from both sides, keep one canonical copy of imports, declarations and pom entries, keep license headers, never reintroduce forbidden tech or I/O into libs/*), then \`git -C ${t.wt} add <files>\` and \`git -C ${t.wt} commit --no-edit\`. If a conflict cannot be resolved sensibly, \`git -C ${t.wt} merge --abort\`, leave that sub-worktree in place, and return ok=false.
 c. After it merged: \`git -C ${REPO} worktree remove --force <sub>\` and \`git -C ${REPO} branch -D <branch>\`.
Then run \`${MVND} -q -f ${t.wt}/pom.xml test-compile\` and set compiled accordingly, putting the first compiler errors in detail if it fails. Do not fix compile errors unless your own conflict resolution caused them. Never touch files outside these worktrees.`,
    { label: `${t.key} ${tag} integrate`, phase: phaseName, schema: INTEGRATE, model: 'sonnet' })
  if (!merged || !merged.ok) stop(`could not integrate parallel wave ${tag}: ${merged ? merged.detail : 'integrator died'}`)
  if (merged.conflicts && merged.conflicts.length) log(`${t.key} ${tag}: resolved conflicts in ${merged.conflicts.join(', ')}`)
  if (!merged.compiled) {
    t.reports.push({ tag, id: 'integrate', title: 'wave merge', model: 'sonnet', effort: '-', status: 'partial', summary: `merged wave does not compile: ${trim(merged.detail, 1500)}`, notes: '' })
  }
}

async function runWaves(t, waves, purpose, phaseName) {
  const ws = normaliseWaves(t, waves, purpose)
  for (const w of ws) await runWave(t, w, phaseName)
  return ws.length
}

// Execute waves, then let the coordinator check the result and add follow-up waves.
async function executeWithCheck(t, waves, purpose, phaseName) {
  let pending = waves
  for (let round = 0; ; round += 1) {
    const ran = await runWaves(t, pending, purpose, phaseName)
    if (!ran) return
    if (round >= cfg.checkRounds) {
      if (cfg.checkRounds > 0) log(`${t.key}: check budget for "${purpose}" used up; continuing to the gate.`)
      return
    }
    const chk = await coordinator(t, `Completeness check for ticket ${t.key} (${t.summary}). Purpose of the work just done: ${purpose}.

The ticket's plan, as you wrote it earlier:
${t.planSummary}

Fleet reports for this ticket so far:
${fleetDigest(t)}

Inspect the actual change in worktree ${t.wt} (branch ${t.branch}, which may hold wip commits): \`git -C ${t.wt} status --porcelain\`, \`git -C ${t.wt} diff main\` (working tree vs main), and read untracked files. Compare against what this purpose required (and, for the implementation purpose, every deliverable, acceptance criterion and Review Focus item of the spec — each should be covered by a test). You may run a quick \`${MVND} -q -f ${t.wt}/pom.xml -pl <module> -am test-compile\`; the full gate runs after you.

If everything is present and plausible, return complete=true with no waves. Otherwise return complete=false with follow-up waves for exactly what is missing or wrong (blocked or partial subtasks included). summary: what you found, a few lines.

${SPLIT_RULES}`, `check (${purpose})`, phaseName, 'medium')
    if (!chk) stop(`coordinator check died during "${purpose}"`)
    if (chk.abort) stop(`coordinator aborted during "${purpose}": ${chk.abort}`)
    if (chk.complete || !chk.waves.length) return
    log(`${t.key}: coordinator found gaps (${trim(chk.summary, 200)}); adding follow-up waves.`)
    pending = chk.waves
  }
}

// ---------------------------------------------------------------------------
// Gates
// ---------------------------------------------------------------------------

async function runGate(t, withSystest, tag) {
  const steps = []
  if (t.dirty || !withSystest) steps.push(`\`${MVND} -f ${t.wt}/pom.xml test\` — the full reactor: compile with -Werror plus every module's tests`)
  if (withSystest) steps.push(`\`cd ${t.wt} && ./systest.sh\` — Docker: unit + integration + live bot/human suite; can take 20+ minutes`)
  const logBase = `${REPORTS}/${t.key}-gate-${tag}`
  const g = await ops(`Run the build gate for ticket ${t.key} in worktree ${t.wt}. Do not edit or fix anything; only run and report.
First \`mkdir -p ${REPORTS}\`. Then run these steps in order, stopping at the first that fails:
${steps.map((s, i) => `${i + 1}. ${s}\n   Log: ${logBase}-${i + 1}.log`).join('\n')}

Run each step with the Bash tool in the background, as \`( <command> ) > <log> 2>&1; echo "GATE_EXIT=$?" >> <log>\`, and wait for its completion notification (if none arrives, use the Monitor tool to wait until the log contains GATE_EXIT). Never use sleep loops. A step passes only if GATE_EXIT=0.
Return pass, failedStep ("mvnd" or "systest", "" on pass), logPath of the failing (or last) log, and failures: on failure, an excerpt of at most 12000 characters with the failing test names, assertion messages, stack-trace heads, and compiler errors/warnings — the lines someone fixing it needs, not the whole log.`, GATE, `${t.key} gate ${tag}${withSystest ? ' +systest' : ''}`, 'Gate')
  if (!g) stop(`gate runner died (${tag})`)
  return g
}

async function gateWithRepair(t, withSystest, tag) {
  if (!t.dirty && !withSystest) return
  for (let round = 0; ; round += 1) {
    const g = await runGate(t, withSystest, `${tag}${round ? `-r${round}` : ''}`)
    if (g.pass) {
      t.dirty = false
      log(`${t.key}: gate ${tag} green${withSystest ? ' (incl. systest)' : ''}.`)
      return
    }
    if (round >= cfg.repairRounds) stop(`gate ${tag} still red (${g.failedStep}) after ${cfg.repairRounds} repair round(s); log ${g.logPath}`)
    log(`${t.key}: gate ${tag} red at ${g.failedStep}; coordinator plans repair round ${round + 1}.`)
    const fix = await coordinator(t, `The ${g.failedStep} gate failed for ticket ${t.key} (${t.summary}) in worktree ${t.wt}. Full log: ${g.logPath}

Failure excerpt:
${g.failures}

Fleet reports so far:
${fleetDigest(t)}

Find the root causes (read the log and the code) and plan the smallest waves that fix them. Fix causes, never symptoms: no weakened tests, gates or warning settings. If a failure is pre-existing on main or environmental (Docker down, port clash) rather than caused by this ticket, set abort and say so. Set complete=false. summary: the root causes, a few lines.

${SPLIT_RULES}`, `repair ${tag} #${round + 1}`, 'Gate', 'high')
    if (!fix) stop(`coordinator died planning a repair for gate ${tag}`)
    if (fix.abort) stop(`gate ${tag} red and coordinator aborted: ${fix.abort}`)
    await executeWithCheck(t, fix.waves, `repair the red ${g.failedStep} gate (${trim(fix.summary, 200)})`, 'Gate')
  }
}

// ---------------------------------------------------------------------------
// One ticket
// ---------------------------------------------------------------------------

async function processTicket(t, entry) {
  if (!cfg.dryRun) {
    const claim = await ops(`Claim ticket ${t.key} and cut its worktree.
1. If ${t.wt} exists, or \`git -C ${REPO} rev-parse --verify --quiet refs/heads/${t.branch}\` succeeds, stop: return ok=false and say a previous attempt is left over (do not remove it).
2. \`st status ${t.key} in-progress\`
3. \`git -C ${REPO} worktree add ${t.wt} -b ${t.branch} main\`
4. \`mkdir -p ${REPORTS}\``, OPS, `${t.key} claim`, 'Pick')
    if (!claim || !claim.ok) stop(`could not claim / cut worktree: ${claim ? claim.detail : 'operator died'}`)
  }

  const where = cfg.dryRun ? REPO : t.wt
  const plan = await coordinator(t, `Plan ticket ${t.key}: ${t.summary}
${cfg.dryRun ? `DRY RUN: nothing will be executed; read from the main checkout ${REPO}.` : `The fleet works in worktree ${t.wt} (branch ${t.branch}, freshly cut from main); read from there.`}

Read before planning: \`st show ${t.key} --json\` (its description is the spec; first line names the spec file), the spec file itself (tickets/ or tickets/done/ under ${where}), tickets/README.md, AGENTS.md, PROJECTS.md, prompts/implement.md, the planning docs the spec references, and the source it touches. Never plan from the ticket text alone.

Return:
- specFile: repo-relative path of the spec file ("" if none).
- needsSystest: true if the ticket changes server, API, UI, persistence or systest behaviour.
- commitMessage: one imperative line in the repo's style ("Add ...", "Fix ..."), without the ticket key.
- summary: what the ticket delivers and the shape of your plan, at most ~10 lines. Every implementer sees this.
- complete=false, and the waves.

${SPLIT_RULES}`, 'plan', 'Plan', 'high')
  if (!plan) stop('coordinator died while planning')
  if (plan.abort) stop(`coordinator refused to plan: ${plan.abort}`)
  t.planSummary = plan.summary
  t.specFile = plan.specFile || ''
  t.needsSystest = plan.needsSystest === true
  t.commitMessage = (plan.commitMessage || '').trim() || t.summary
  const planned = normaliseWaves(t, plan.waves, 'plan')
  entry.plan = planned.map((w, i) => `w${i + 1}: ${w.map(s => `${s.id} [${pickModel(s.model, 'sonnet')}/${s.effort}]`).join(', ')}`)
  log(`${t.key} plan: ${entry.plan.join(' | ')}`)
  if (cfg.dryRun) {
    entry.status = 'planned (dry run)'
    entry.planDetail = plan
    return
  }

  // Build
  await executeWithCheck(t, plan.waves, 'implement the ticket', 'Build')
  await gateWithRepair(t, false, 'impl')

  // Pipeline review (prompts/review.md)
  const rv = await agent(`Perform the pipeline review defined in ${REPO}/prompts/review.md for ticket ${t.key} ({{ticket.id}} = ${t.key}; summary and description via \`st show ${t.key} --json\`; spec file ${t.specFile || 'per the description'}).

Adaptations for this line:
- The change is not on main yet. It lives in worktree ${t.wt} (branch ${t.branch}) as wip commits plus uncommitted edits. "Read the diff" means \`git -C ${t.wt} diff main\` plus \`git -C ${t.wt} status --porcelain\` (read untracked files in full).
- Fix everything you find, as the prompt says, by editing files under ${t.wt} only.
- Do NOT commit, push, or change st status: the line commits after the deep review. Do not run ./systest.sh (the final gate runs it); do run \`${MVND} -f ${t.wt}/pom.xml test\` and leave it green with zero warnings.
- A fleet of agents built this in pieces, so look hard at the seams between them: duplicated helpers, inconsistent names, half-wired features, tests that only cover one side.

Fleet reports:
${fleetDigest(t)}

Return every finding with whether you fixed it, a verdict for every Review Focus item, changedFiles (did you edit anything), and mvndGreen.`,
    { label: `${t.key} review.md`, phase: 'Review', schema: REVIEW, model: 'opus', effort: 'high' })
  if (!rv) stop('pipeline reviewer died')
  if (rv.changedFiles || rv.findings.some(f => f.fixed)) t.dirty = true
  const unfixed = rv.findings.filter(f => !f.fixed)
  entry.review = { findings: rv.findings.length, fixed: rv.findings.length - unfixed.length, unfixed: unfixed.map(f => `${f.where}: ${f.problem}`) }
  log(`${t.key}: review.md — ${rv.findings.length} finding(s), ${unfixed.length} left unfixed.`)
  await gateWithRepair(t, false, 'review')

  // Deep review (prompts/deep-review.md, report-only)
  const reportPath = `${REPORTS}/${t.key}-deep-review.md`
  const deep = await agent(`Perform the human-gated deep review in ${REPO}/prompts/deep-review.md with this header:
TICKET: ${t.key}
ACTION: report-only

Adaptations for this line:
- The ticket's code is not on main yet: it is in worktree ${t.wt} (branch ${t.branch}) as wip commits plus uncommitted edits relative to main. §1 step 4 (find commits by grep) does not apply; map the ticket's files with \`git -C ${t.wt} diff --stat main\` and \`git -C ${t.wt} status --porcelain\`, and read the current files in ${t.wt} in full (§2).
- report-only: no lasting edits to files in ${t.wt}. If you add a throwaway test inside the worktree to prove a finding, delete it before you finish and quote it in the proof. Run no state-changing git commands.
- Run mvnd against the worktree (\`${MVND} -f ${t.wt}/pom.xml ...\`). Do not run ./systest.sh: the line runs it as the final gate after fixes; say "deferred to the line's final gate" in your verification section.
- The pipeline review (prompts/review.md) already ran: ${rv.findings.length} finding(s), ${unfixed.length} unfixed${unfixed.length ? `: ${unfixed.map(f => trim(`${f.where}: ${f.problem}`, 200)).join('; ')}` : ''}. Its Review Focus verdicts: ${trim(compact(rv.reviewFocus), 3000)}. Assume it missed things.
- The spec file is not yet moved to tickets/done/ and st still says in-progress; that is expected at this point, not a finding.
- Write the full four-section report (§5) to ${reportPath} and return the same findings structurally (ids like ${t.specFile ? 'NN-H1' : '<key>-H1'}), with reportPath.`,
    { label: `${t.key} deep-review.md`, phase: 'Deep review', schema: DEEP, model: 'opus', effort: 'high' })
  if (!deep) stop('deep reviewer died')
  const toVerify = deep.findings.filter(f => f.severity === 'HIGH' || f.severity === 'MEDIUM')
  log(`${t.key}: deep review — ${deep.findings.map(f => f.id).join(', ') || 'no findings'}; verifying ${toVerify.length}.`)
  const verdicts = await parallel(toVerify.map(f => () => agent(`A deep reviewer of KotharWorks ticket ${t.key} claims a ${f.severity} finding about the change in worktree ${t.wt} (branch ${t.branch}, compared with main via \`git -C ${t.wt} diff main\`).

${f.id} at ${f.where}
Scenario: ${f.scenario}
Proof offered: ${f.proof}

Try to REFUTE it by reading the code and tests. Read-only: no edits, no builds, no state-changing git. Verdicts: "confirmed" (the failure is real as stated, or real in a slightly different form — say how), "refuted" (cite the lines that make it impossible or show it is out of this ticket's scope), "uncertain" (you could not settle it either way).`,
    { label: `${t.key} verify ${f.id}`, phase: 'Deep review', schema: VERDICT, model: f.severity === 'HIGH' ? 'opus' : 'sonnet' })
    .then(v => ({ f, v }))))
  const judged = verdicts.filter(Boolean)
  const confirmed = judged.filter(x => x.v && x.v.verdict === 'confirmed').map(x => x.f)
  const refuted = judged.filter(x => x.v && x.v.verdict === 'refuted').map(x => x.f.id)
  const fixSev = cfg.fixDeep === 'none' ? [] : cfg.fixDeep === 'high' ? ['HIGH'] : ['HIGH', 'MEDIUM']
  const toFix = confirmed.filter(f => fixSev.includes(f.severity))
  entry.deep = {
    reportPath: deep.reportPath || reportPath,
    dependentsClear: deep.dependentsClear,
    counts: ['HIGH', 'MEDIUM', 'LOW', 'QUESTION'].map(s => `${s}:${deep.findings.filter(f => f.severity === s).length}`).join(' '),
    confirmed: confirmed.map(f => f.id),
    refuted,
    fixed: [],
    outstanding: deep.findings.filter(f => !toFix.includes(f) && !refuted.includes(f.id)).map(f => `${f.id} [${f.severity}] ${trim(f.scenario, 160)}`),
  }

  // Fix confirmed findings (+ anything review.md left unfixed)
  if (toFix.length || unfixed.length) {
    const fp = await coordinator(t, `Plan fixes for ticket ${t.key} (${t.summary}) in worktree ${t.wt}.

Confirmed deep-review findings to fix (full report: ${entry.deep.reportPath}):
${toFix.map(f => `- ${f.id} [${f.severity}] ${f.where}: ${f.scenario}\n  proof: ${trim(f.proof, 800)}`).join('\n') || '(none)'}

Pipeline-review findings left unfixed:
${unfixed.map(f => `- [${f.severity}] ${f.where}: ${f.problem}`).join('\n') || '(none)'}

Every fix gets a regression test (unit test in the owning module, or a live test under systest/.../regression/ when only observable black-box) whose name or comment cites the finding ID and ticket key. Put the finding ID in each subtask's id or title. Set complete=false. summary: one line per finding saying how it will be fixed. If a finding should not be fixed (it is wrong, or needs the owner's decision), leave it out and say why in summary.

${SPLIT_RULES}`, 'fix plan', 'Fix', 'high')
    if (!fp) stop('coordinator died planning fixes')
    if (fp.abort) stop(`coordinator aborted fix planning: ${fp.abort}`)
    await executeWithCheck(t, fp.waves, `fix review findings ${toFix.map(f => f.id).join(', ') || '(review.md leftovers)'}`, 'Fix')
    entry.deep.fixed = toFix.map(f => f.id)
  }

  // Final gate
  const withSystest = cfg.systest === 'always' || (cfg.systest === 'auto' && t.needsSystest)
  await gateWithRepair(t, withSystest, 'final')
  if (!withSystest) entry.notes.push(cfg.systest === 'never' ? 'systest skipped (systest=never)' : 'systest skipped: coordinator judged no server/API/UI/persistence change')

  // Merge
  const fleetLines = t.reports.filter(r => r.id !== 'integrate').map(r => `- ${r.tag}:${r.id} [${r.model}] ${r.title}`)
  const msg = [
    `${t.key}: ${t.commitMessage}`,
    '',
    trim(t.planSummary, 1200),
    '',
    'Fleet:',
    ...fleetLines,
    '',
    `Pipeline review (prompts/review.md): ${entry.review.findings} finding(s), ${entry.review.fixed} fixed in review${unfixed.length ? `, ${unfixed.length} handed to the fix wave` : ''}.`,
    `Deep review (prompts/deep-review.md): ${entry.deep.counts}; fixed ${entry.deep.fixed.join(', ') || 'none'}; refuted ${refuted.join(', ') || 'none'}; outstanding ${entry.deep.outstanding.map(o => o.split(' ')[0]).join(', ') || 'none'}.`,
    `Gates: mvnd test${withSystest ? ' + ./systest.sh' : ''} green.`,
    '',
    TRAILER,
  ].join('\n')
  const specMove = t.specFile && /^tickets\/[^/]+\.md$/.test(t.specFile) && !/README\.md$/.test(t.specFile)
  const m = await ops(`Land ticket ${t.key}. Follow these steps exactly, stop at the first failing step and report it in failedStep with its stderr in detail.
1. Append a "## Line actions" section to ${entry.deep.reportPath} (create the file if missing) listing: fixed by the line: ${entry.deep.fixed.join(', ') || 'none'}; refuted by verifiers: ${refuted.join(', ') || 'none'}; outstanding for the owner: ${entry.deep.outstanding.join(' | ') || 'none'}.
2. ${specMove ? `If ${t.wt}/${t.specFile} exists: \`mkdir -p ${t.wt}/tickets/done && git -C ${t.wt} mv ${t.specFile} tickets/done/\`.` : 'No spec file to move; skip.'}
3. \`git -C ${t.wt} add -A\`; \`git -C ${t.wt} reset --soft $(git -C ${t.wt} merge-base main HEAD)\`. If \`git -C ${t.wt} diff --cached --quiet\` exits 0, fail with "no changes".
4. Write the commit message between the markers below, verbatim, to a temp file and \`git -C ${t.wt} commit -F <file>\`.
5. \`git -C ${REPO} rev-parse --abbrev-ref HEAD\` must print main; then \`git -C ${REPO} merge --ff-only ${t.branch}\`. Record \`git -C ${REPO} rev-parse --short HEAD\` as sha.
${cfg.push
  ? `6. \`git -C ${REPO} push origin main\` (failure = step fails; set pushedOrigin). Then \`git -C ${REPO} push ${SRHT} main\` (failure is only a warning; set pushedSrht).`
  : '6. Pushing is disabled for this run: skip, pushedOrigin=false, pushedSrht=false.'}
7. \`st status ${t.key} done\` (set stDone).
8. Promote dependents whose dependencies are all done: run this and put each PROMOTED key in promoted:
${PROMOTE_SH}
9. \`git -C ${REPO} worktree remove ${t.wt}\` and \`git -C ${REPO} branch -d ${t.branch}\` (failures are warnings).

-----BEGIN COMMIT MESSAGE-----
${msg}
-----END COMMIT MESSAGE-----`, MERGE, `${t.key} merge`, 'Merge', 'sonnet')
  if (!m) stop('merge operator died — check main, the worktree and st by hand')
  entry.sha = m.sha
  ;(m.warnings || []).forEach(w => entry.notes.push(w))
  if (!m.ok) {
    const after = ['5', '6', '7', '8'].some(s => String(m.failedStep).startsWith(s)) || m.sha
    stop(`merge failed at step ${m.failedStep}: ${trim(m.detail, 600)}${after ? ` — main may already contain the commit${m.sha ? ` (${m.sha})` : ''}; finish the remaining steps by hand (${cfg.push ? `push origin + ${SRHT}, ` : ''}st status ${t.key} done, promote dependents).` : ` Worktree left at ${t.wt}.`}`)
  }
  if (cfg.push && !m.pushedSrht) entry.notes.push('SourceHut mirror push failed — push it by hand')
  if (m.promoted && m.promoted.length) log(`${t.key}: promoted ${m.promoted.join(', ')} to ready.`)
  entry.status = 'merged'
  log(`${t.key}: merged as ${m.sha}${cfg.push ? ', pushed' : ''}, st done.`)
}

// ---------------------------------------------------------------------------
// The line
// ---------------------------------------------------------------------------

phase('Preflight')
const pre = await ops(`Preflight for the ticket line. Change nothing; run each check and classify what you find.
1. \`git -C ${REPO} rev-parse --abbrev-ref HEAD\` must print main — otherwise a problem.
2. \`git -C ${REPO} status --porcelain --untracked-files=no\` must be empty — otherwise a problem listing the files (worktrees branch from the last commit, so uncommitted edits are invisible to the fleet and can block the fast-forward).
3. \`git -C ${REPO} status --porcelain --untracked-files=all -- tickets prompts AGENTS.md systest\` — any untracked files are a warning naming them (agents in worktrees cannot see them).
4. \`st list --ready --project KOTHARWORKS --json > /dev/null\` must succeed — otherwise a problem ("samtasker unreachable — is wg0 up?").
5. \`${MVND} --version\` must succeed — otherwise a problem.
6. \`python3 --version\` must succeed — otherwise a problem.
7. \`docker info > /dev/null && docker compose version\` — ${cfg.systest === 'always' ? 'failure is a problem' : cfg.systest === 'never' ? 'skip this check' : 'failure is a warning (systest and Testcontainers tests will fail)'}.
${cfg.push
  ? `8. \`git -C ${REPO} remote\` must list origin — otherwise a problem. \`git -C ${REPO} rev-list --count origin/main..main\`: if > 0, a warning that the first push will carry those commits.`
  : '8. Pushing is disabled; skip remote checks.'}
9. \`git -C ${REPO} worktree list\` — any worktree under ${REPO}/worktrees/ is a warning naming it (a leftover attempt).
ok = no problems.`, PREFLIGHT, 'preflight', 'Preflight')
if (!pre) return { conclusion: 'Stopped: the preflight agent died.', config: cfg, tickets: [] }
;(pre.warnings || []).forEach(w => log(`preflight warning: ${w}`))
if (!pre.ok) return { conclusion: `Stopped in preflight, nothing touched: ${pre.problems.join('; ')}`, config: cfg, warnings: pre.warnings, tickets: [] }

log(`Line opens: ${cfg.tickets.length ? `tickets ${cfg.tickets.join(', ')}` : `up to ${cfg.limit} ticket(s), ${cfg.label ? `label '${cfg.label}'` : 'all labels'}`}${cfg.dryRun ? ' (DRY RUN)' : ''}; push=${cfg.push}, systest=${cfg.systest}, fixDeep=${cfg.fixDeep}.`)

const ledger = []
const seen = []
let stopReason = null

for (let n = 0; n < cfg.limit && stopReason === null; n += 1) {
  phase('Pick')
  const explicit = cfg.tickets[n]
  const pick = await ops(explicit
    ? `Look up ticket ${explicit}: \`st show ${explicit} --json\`. If it does not exist, or its status is done or cancelled, return found=false with the reason in error. Otherwise return found=true, key, summary, and spec = the first line of its description. promoted=[].`
    : `Pick the next ticket. Run exactly:
${PICK_SH(cfg.label, seen)}
If it prints a JSON object, return found=true with its key, summary and spec, promoted=[], error="".
${cfg.dryRun
  ? 'If it prints nothing, return found=false (dry run: do not promote anything).'
  : `If it prints nothing, promote unblocked backlog tickets by running:
${PROMOTE_SH}
Put each PROMOTED key in promoted; if any were promoted, run the first command once more and return its result; otherwise found=false.`}
If any command fails, return found=false and put the command and its stderr in error.`, PICK, `pick #${n + 1}`, 'Pick')
  if (!pick) { stopReason = 'ticket picker died'; break }
  if (pick.promoted && pick.promoted.length) log(`Promoted ${pick.promoted.join(', ')} to ready.`)
  if (pick.error) { stopReason = pick.error; break }
  if (!pick.found) { log('No more ready tickets — queue drained.'); break }
  seen.push(pick.key)

  const t = {
    key: pick.key,
    summary: pick.summary,
    wt: `${REPO}/worktrees/${pick.key}`,
    branch: `ql/${pick.key}`,
    reports: [],
    waveNo: 0,
    dirty: false,
    planSummary: '',
    specFile: '',
    needsSystest: false,
    commitMessage: '',
  }
  const entry = { key: t.key, summary: trim(t.summary, 120), status: 'failed', sha: '', notes: [] }
  ledger.push(entry)
  log(`Taking ${t.key}: ${t.summary}`)
  try {
    await processTicket(t, entry)
  } catch (e) {
    const why = e && e.message ? e.message : String(e)
    entry.notes.push(why)
    stopReason = `${t.key}: ${why}`
    if (!cfg.dryRun && entry.status !== 'merged') entry.notes.push(`ticket left in-progress; attempt in ${t.wt} (branch ${t.branch})`)
  }
  entry.fleet = t.reports.map(r => `${r.tag}:${r.id} [${r.model}/${r.effort}] ${r.status}`)
}

const merged = ledger.filter(e => e.status === 'merged')
return {
  conclusion: cfg.dryRun
    ? `Dry run: planned ${ledger.length} ticket(s); nothing changed.${stopReason ? ` Stopped: ${stopReason}` : ''}`
    : stopReason
      ? `Line stopped after ${merged.length} merge(s): ${stopReason}`
      : `Queue drained: ${merged.length} ticket(s) merged${cfg.push ? ' and pushed' : ''}.`,
  stopReason,
  config: cfg,
  preflightWarnings: pre.warnings,
  tickets: ledger,
}
