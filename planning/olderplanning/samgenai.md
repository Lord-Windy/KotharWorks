# SamGenAI — Technical Requirements Document (working draft)

Linear genetic programming (LGP) genetics layer over samvm, plus the
samgenai-studio training app. This document starts as a TRD-lite: the public
API contract, the scaffold/stub split, and the app design. Grow it into a
full TRD as the real genetics land.

## 1. Position

samvm's TRD (§1.1, decision D6) deliberately keeps genetics out of the VM:
samvm owns the genome (`SamVMProgram`), crossover surgery
(`samvm_program_insert`/`cut`), dedup (`hash`/`equal`), seeded random
programs, and a deterministic, re-entrant interpreter. samgenai is the layer
that TRD reserved: **population management, selection, mutation policy,
crossover policy, and fitness** — everything above the genome.

Dependencies: samvm (→ samrena, samdata). Nothing else. samgenai does not
depend on samhttp/samhtml; those belong to the studio app.

## 2. Problem model

Fitness is **maximized** everywhere. Two problem kinds share one run handle:

### 2.1 Dataset problems (`SAMGENAI_PROBLEM_DATASET`)

Static fitness cases: row-major input/output tables, one `samvm_run` per
case. Built-in metrics (real, implemented):

- `samgenai_fitness_neg_mae` — negative mean absolute error per output
  value; 0.0 is perfect, unrunnable genomes score `-FLT_MAX`.
- `samgenai_fitness_hits` — fraction of outputs within a tolerance
  (`user = float *`, default 0.05); 1.0 is perfect.

Custom metrics are plain `SamGenaiFitnessFn` callbacks.

### 2.2 Environment problems (`SAMGENAI_PROBLEM_ENVIRONMENT`)

Interactive episodic evaluation — the controller/game seam, added for the
"eventually plays video games" goal. The library owns the loop:

```
for episode in episode_count:
    reset(ctx)
    for step in max_steps:
        observe(ctx, inputs)          # world → observation
        samvm_run(vm, genome, ...)    # genome thinks
        reward += act(ctx, outputs)   # action → world, returns reward
        if done(ctx): break
fitness = mean reward over episodes
```

Environments are four callbacks (`reset`/`observe`/`act`/`done`) plus widths
and episode counts — any game or simulation implements them. The loop is
real today; it contains no genetics.

**Memory note:** `samvm_run` zeroes the calc registers per call, so a genome
has no hidden state between steps within an episode. Environments that want
history-sensitive behavior must feed prior observations/actions back through
their own inputs. A persistent-register VM mode would remove the workaround —
that is a future samvm decision, not a samgenai one.

## 3. Config

| Field | Default | Notes |
|---|---|---|
| `population_size` | 200 | min 4 |
| `init_genome_len` | 16 | random genomes at generation 0 |
| `min_genome_len` / `max_genome_len` | 2 / 128 | deletion/insertion clamps |
| `tournament_size` | 4 | in [2, population] |
| `elite_count` | 2 | carried unchanged; ≤ pop/2 |
| `max_generations` | 1000 | 0 = unbounded |
| `instr_mutation_rate` | 0.10 | per instruction, field-addressed rewrites |
| `insertion_rate` / `deletion_rate` | 0.05 / 0.05 | segment grow/shrink |
| `crossover_rate` | 0.70 | per couple |
| `success_fitness` | 0.95 | stop when best ≥ this (per problem!) |
| `seed` | 42 | one `SamRng` per run |

`success_fitness` is a per-problem override in practice: the studio sets
−0.005/−0.01 for the regression presets (neg MAE), 24.0 for the gem hunter,
490.0 for the cart pole.

Validation lives in `samgenai_config_validate`; `create` enforces it.

## 4. Scaffold / stub contract

**Real today** (keep, they are contract-complete):

- `samgenai_config_default` / `_validate`, `samgenai_error_string`
- `samgenai_create` / `_destroy` / accessors — real handle, real `SamVM`,
  real seeded `SamRng`, all arena-owned
- `samgenai_fitness_neg_mae` / `_hits`
- `samgenai_evaluate` — dispatches on problem kind; the environment episode
  loop is genuine

**Synthetic (`// STUB:` in src/samgenai.c — grep and replace):**

- `init_population` fabricates `population_size` fitnesses around a
  problem-flavored anchor (env: 15% of success; dataset: success − 50) and
  gives rank 0 a **real** `samvm_program_random` genome, so disassembly,
  execution, prediction tables, and replays work end-to-end through genuine
  samvm.
- `evolve_step` advances `generation` and walks `best_fitness` along a
  decaying curve toward `success_fitness` with damped noise; mean trails
  best; `unique_genomes` and `mean_genome_len` drift; every 16 generations
  rank 0 gets a fresh random genome (arena churn accepted in stub mode).
- `stats`/`best`/`individual`/`population_size` expose that state;
  `stats.synthetic` is true and `samgenai_stub_active()` returns true.
  Both the function and the field disappear as real genetics land.

Replacement order suggestion: `init_population` → `evolve_step`
(evaluation loop → selection → variation) → per-generation stats plumbing
(unique counts via `samvm_program_hash`, mean length) → delete
`stub_active`.

## 5. The studio app (`apps/samgenai-studio`, port 8095)

samhttp-style: `main.c` composition root, `pages.c` routes, per-request
arenas, SIGINT-driven shutdown, `--self-test` on an ephemeral port (ctest
`samgenai_studio_self_test`). Modules:

- `problems.c` — the registry: `quartic` (x⁴+x³+x²+x), `wave`
  (sin·x + x²/4), `mexhat` ((1−x²)e^(−x²/2)) datasets; VM `{1,8,1,512}`.
- `env_grid.c` — gem hunter: 12×12 boards (4 hand-authored layouts, no
  enclosures), 8-float observation (position, nearest-gem direction, wall
  distances), argmax over 4 outputs, +10/gem, −0.1/step, 60-step episodes.
- `env_pole.c` — cart-pole: Barto dynamics, 4-float state, argmax over 2
  outputs, ±10 N, failure |x|>2.4 or |θ|>12°, +1/step, 500-step episodes,
  3 jittered starts.
- `trainer.c` — mutex-guarded run state; one `Samrena` per run (destroyed
  on reset); a worker thread ticks `evolve_step` at 20 ms while running;
  `trainer_step` for manual stepping; 512-point history ring.
- `pages.c` — dashboard (problem picker, start/stop/step/reset, stats
  table), `/best` (disassembly + predictions/episode summary), JSON
  `/api/status` and kind-aware `/api/play`, `/health`, 404.

The dashboard is the repo's first JavaScript: ~150 lines of vanilla ES5
inline (`PAGE_JS` in pages.c) polling `/api/status` every second — stat
spans, best/mean SVG chart, and the replay animation (regression overlay,
animated grid board, animated cart+pole). Everything also renders
server-side without JS; the tables are the fallback.

Routes are GET-with-query on purpose (local single-user tool, HTML forms
submit natively without JS). `/start` accepts `go=0` to start paused so
stepping is manual and deterministic.

## 6. Test plan

- `samgenai_unit` (ctest): error strings; config defaults + validation
  rejections; create/destroy NULL-safety; metrics exact on hand-written
  MOVI genomes (−1.5 MAE, tolerance hits); `evaluate` exact on both kinds
  (dataset: −1.5; toy env: 5.0 and a done()-capped 3.0; width mismatch →
  `_INVALID_ARG`); stub contract (generation counting, generation cap,
  disassemblable best genome, `stub_active`).
- `samgenai_studio_self_test` (ctest): drives every route over a real
  socket — pages, actions, all three problem kinds through `/api/play`,
  reset-to-idle, 404 — with paused starts so generation counts are exact.
- `smoke.sh`: manual curl pass against the live server.

## 7. Future work (explicitly out of the current slice)

- Real genetics (everything in §4's synthetic list).
- Persistent calc registers across episode steps (samvm decision).
- Parallel fitness evaluation (samvm_run is re-entrant; needs only caller
  locking — likely a thread-pool inside `evolve_step` later).
- Save/load of runs via `samvm_program_pack`.
- GPU evaluation via samcl (samvm TRD mentions translation-readiness).
