# SamVM — Technical Requirements Document

## 1. Overview

### 1.1 Purpose

SamVM is a register virtual machine library for the Ptah project, built as the
execution substrate for **linear genetic programming (LGP)**. In LGP, an
individual ("genome") is a flat sequence of instructions operating on a fixed
register file; evolution mutates and crosses over instruction segments and
evaluates fitness by running the program on input data. SamVM provides
everything below the genetics: instruction encoding, program construction and
surgery, a deterministic interpreter with termination guarantees,
disassembly, serialization, and random-program generation. Selection,
mutation policy, crossover policy, and fitness live with the user's LGP
layer — SamVM is deliberately ignorant of them.

The design is shaped by one forward-looking constraint (**the guiding
star**): a program must be mechanically translatable to C or OpenCL C for
long-lived production runs of evolved champions. Translation is **not** in
scope for v1, but every v1 design decision below is checked against it
(§6).

### 1.2 Position in the Dependency Graph

```
samrena → samdata → samvm
```

SamVM depends on samrena (arena allocation — every allocation routes through
a caller-provided `Samrena *`) and samdata (`SamRng` for random program
generation). No other Ptah libraries, no external dependencies.

### 1.3 Design Principles

1. **Genome-friendly.** Instructions are fixed-width, field-addressed, and
   memcpy-able, so LGP crossover is literally segment copying and mutation is
   field randomization. Random programs are always structurally valid.
2. **Total determinism.** Same program + same inputs + same version →
   bitwise-identical outputs and step counts. No undefined floats escape the
   machine: every observable register value is finite (§4.4).
3. **Guaranteed termination.** Every run stops within a configured
   instruction budget regardless of what evolved code contains. Budget
   exhaustion is a normal outcome, not an error.
4. **Translatable by construction.** Flat control flow (absolute jump
   targets), no pointers, no calls, no dynamic memory, all ops map to single
   C expressions / OpenCL builtins (§6).
5. **Ergonomic for hand-written programs.** The user writes the LGP layer and
   will hand-assemble test programs: compact emit macros, label/patch for
   jump targets, a disassembler, and precise documented semantics.
6. **Re-entrant execution.** The interpreter takes `const SamVM *` and keeps
   no per-run state on the handle, so a population can later be evaluated in
   parallel by the LGP layer with no samvm changes.

### 1.4 Key Decisions at a Glance

| # | Decision | Where |
|---|---|---|
| D1 | Register value type is `float` (f32), not f64 | §3.2 |
| D2 | Instructions are an 8-byte struct `{op, dst, src1, src2, aux}` | §3.1 |
| D3 | Control flow is raw absolute jumps + instruction budget (no structured loop ops) | §3.3 |
| D4 | Immediates via `_I` opcode variants; no constant-pool register bank | §3.4 |
| D5 | `SamVMProgram` is a plain struct (not `SamrenaVector`) | §3.5 |
| D6 | Random-program generation is public API (dep on samdata), genetics stay out | §3.6 |
| D7 | Budget exhaustion / tolerant decode of junk fields never error at run time | §4.6 |
| D8 | Determinism strength split: bitwise everywhere except libm-hosted MATH ops (ULP tolerance) | §4.6 |
| D9 | `-ffast-math` rejected at compile time (`#error` on `__FAST_MATH__`) | §4.4 |
| D10 | Program hash/equal helpers for LGP dedup and fitness caching | §4.7 |

## 2. Design Considerations

### 2.1 LGP Workload Profile

| Property | Consequence |
|---|---|
| Programs are short (tens–hundreds of instructions) but runs are massive (millions of evaluations) | Interpreter must be a tight switch loop; no per-run heap traffic; registers on stack |
| Genomes are mutated at random | VM must execute *any* syntactically plausible instruction without crashing: out-of-range fields degrade to defined behavior (§4.6) |
| Crossover splices instruction segments | Program storage must be a flat memcpy-able array with segment surgery helpers |
| Champions must be inspectable and archivable | Disassembler + packed serialization; regression corpus built from both |
| Fitness evaluation of a population is embarrassingly parallel (future) | `samvm_run` re-entrant on shared const VM/config/program |
| Introns and non-terminating loops are common in random code | Instruction budget is the only loop-safety mechanism needed |

### 2.2 Compilability Constraints (the guiding star, v1 invariants)

| Invariant | Why it matters for C / OpenCL translation |
|---|---|
| Fixed 8-byte instructions, absolute u32 jump targets | Each instruction becomes one labeled C statement; a jump is exactly `goto L<target>` |
| Register file is a flat f32 array, ≤ 255 entries | C: local array; OpenCL: `private` array |
| No pointers, calls, recursion, or allocation inside programs | Translated code is a pure function of the input registers |
| Every op is one C expression (`+ - * / fminf fmaxf fabsf sqrtf sinf cosf expf logf`) | 1:1 statement emission; OpenCL has all of these as builtins |
| Protections and sanitization specified exactly (§4.4) | The translator emits the identical guards, preserving semantics |
| Termination via instruction budget | Translator emits a step-counter guard on backward jumps |
| f32 throughout | Portable to every OpenCL device (f64 needs `cl_khr_fp64`) |

## 3. Option Analysis

### 3.1 Instruction Encoding

| Option | Pros | Cons |
|---|---|---|
| **A. Struct of fields, 8 bytes** `{u8 op, dst, src1, src2; union {f32; u32} aux}` | Self-documenting; mutation writes named fields; no encode/decode helpers; exactly 8 bytes so still memcpy/hash/serialize-friendly; `_Static_assert` pins the size | Slightly larger than a bit-packed word would need to be (irrelevant at these sizes) |
| B. Packed `uint64_t` word | Dense genome; bit-level mutation possible | Needs encode/decode macros everywhere; bit-flip mutation creates invalid encodings; unreadable in a debugger |
| C. Variable-length byte stream | Smallest | Decoding cost in the hot loop; segment crossover needs a decoder; hostile to 1:1 translation |

**Recommended: A.** LGP mutation wants *valid* randomness, not bit noise, and
field-addressed structs make both the interpreter and the user's mutation
code legible. The 8-byte fixed width preserves every benefit of B except
literal bit-packing. **Decision:** struct encoding, `_Static_assert(sizeof(SamVMInst) == 8)`.

### 3.2 Register Value Type

| Option | Pros | Cons |
|---|---|---|
| **A. `float` (f32)** | Universal OpenCL support; halves register-file memory; typical for LGP symbolic regression | Less precision than f64 for extreme-scale problems |
| B. `double` (f64) | Numeric headroom | Needs `cl_khr_fp64` on GPU; breaks the OpenCL guiding star on many devices |
| C. Tagged variants (int/float registers) | Expressiveness | Doubles mutation space and translation surface; classic LGP needs none of it — comparisons live in registers as 0/1 floats |

**Recommended: A.** The guiding star (compile to OpenCL) decides it. If a
genuine f64 need appears later it becomes a v2 parallel API / compile-time
flavor, not a v1 toggle. **Decision:** f32 only.

### 3.3 Control Flow

| Option | Pros | Cons |
|---|---|---|
| **A. Raw jumps (`JMP`/`JZ`/`JNZ`, absolute targets) + instruction budget** | Minimal opcode surface; loops *are* backward jumps; translates 1:1 to `goto`; budget alone guarantees termination | Hand-writing needs label/patch helpers (provided) |
| B. Structured loop ops (`LOOP n, body`) | Cannot express unstructured jumps | Extra semantics to specify and translate; constrains mutation (a splice that breaks a loop pair makes invalid programs); classic LGP uses plain branches |
| C. Compare-and-branch (`JLT r1, r2, target`) | Fewer instructions per compare | More branch opcodes; boolean-in-register composition is better mutation material (a `LT` result can feed arithmetic *and* branches) |

**Recommended: A**, with comparisons as value-producing ops (§4.3) so
condition composition comes free. **Decision:** three branch ops, absolute
targets, budget-guarded.

### 3.4 Immediate Operands

| Option | Pros | Cons |
|---|---|---|
| **A. `_I` opcode variants** (`ADD` vs `ADD_I`) using the `aux.f` field | Uniform 3-register form for all register ops; immediates only where they pay (arith + compares); decode stays trivial; hand-written code reads naturally | Opcode count roughly doubles for arith/cmp (fits u8 trivially) |
| B. Constant-pool bank (`c[i]` registers, all ops reg-reg) | Zero variants; classic Brameier & Banzhaf setup | Extra bank semantics; constants need separate mutation operators; hand-writing constants is clunkier |
| C. Flag bit selecting reg-vs-imm for src2 | Half the opcodes | Hidden mode bit in every decode path; worse for mutation (flag must be mutated too) and for translation |

**Recommended: A** for v1 ergonomics. A constant pool is an LGP-layer
optimization that can be added later as pure sugar (emit `MOVI` +
register ops) without touching the VM. **Decision:** `_I` variants for
`ADD/SUB/MUL/DIV` and all six comparisons.

### 3.5 Program Storage

| Option | Pros | Cons |
|---|---|---|
| **A. Plain struct** `SamVMProgram {code, length, capacity}` | Direct `code[i]` indexing in the interpreter hot loop; trivially memcpy-able for crossover; no `void*` casting | Reimplements growth (arena push + copy — ~30 lines, same as SamString) |
| B. `SamrenaVector` (samrena's dynamic array) | Reuses existing growth code | `void*` element API is friction on the hot path and for genetics; wrapper indirection for zero real gain at fixed element size |

**Recommended: A.** The program *is* the genome; it should look like one.

### 3.6 Random Program Generation — Placement

| Option | Pros | Cons |
|---|---|---|
| **A. Public samvm API** (`samvm_program_random`) | Seeds initial LGP populations for the user; powers the fuzz and regression suites; guarantees structural validity rules live in one place | Pulls samdata (`SamRng`) into the link |
| B. Test-only helper | No samdata dep | User's LGP layer reimplements it worse |

**Recommended: A.** samdata is a stable in-repo dep used by most libraries;
the function emits only *valid* programs and knows nothing about fitness
distributions — that tuning stays with the user's layer.

## 4. Specification

Single-component library: one umbrella header, per AGENTS.md layout rules.

```
libs/samvm/
├── CMakeLists.txt
├── include/
│   └── samvm.h              # entire public API
├── src/
│   ├── samvm.c              # config, lifecycle, opcode table, error strings
│   ├── samvm_program.c      # append/label/patch, copy/cut/insert, validate, random
│   ├── samvm_run.c          # interpreter loop
│   └── samvm_codec.c        # pack/unpack, disassemble
└── test/
    ├── samvm_test.c         # unit suite
    ├── samvm_fuzz.c         # seeded random-program robustness suite
    ├── samvm_perf.c         # throughput smoke test
    ├── samvm_regress.c      # regression corpus runner (--bless to regenerate)
    └── cases/               # regression corpus (checked in)
```

### 4.1 Configuration & Lifecycle

```c
typedef struct {
    uint8_t  n_inputs;   /* read-only input registers            */
    uint8_t  n_calc;     /* read-write calculation registers     */
    uint8_t  n_outputs;  /* output registers, read after run     */
    uint32_t max_steps;  /* instruction budget per run           */
} SamVMConfig;

void   samvm_config_default(SamVMConfig *cfg);  /* 4, 8, 2, 1024 */
SamVM *samvm_create(Samrena *arena, const SamVMConfig *cfg); /* NULL cfg = defaults */
void   samvm_destroy(SamVM *vm);                             /* NULL-safe, arena-backed */
```

Limits (enforced at create, `SAMVM_ERROR_INVALID_ARG` otherwise):
`n_inputs + n_calc + n_outputs <= 255` (u8 register indices),
`max_steps >= 1`. Program length limit: `SAMVM_MAX_PROGRAM_LEN` (65535).

```c
#define SAMVM_VERSION_MAJOR 1
#define SAMVM_VERSION_MINOR 0
```

Bumped whenever opcodes or semantics change, so the LGP layer and corpus
tooling can detect stale champions against the §4.6 determinism contract.

### 4.2 Instruction Encoding

```c
typedef struct {
    uint8_t op;     /* SamVMOp                                   */
    uint8_t dst;    /* destination register index                */
    uint8_t src1;   /* first source register index               */
    uint8_t src2;   /* second source register index (RR forms)   */
    union {
        float    f; /* immediate operand  (*_I forms)            */
        uint32_t u; /* absolute jump target (JMP/JZ/JNZ)         */
    } aux;
} SamVMInst;        /* exactly 8 bytes, _Static_assert in header */
```

Emit macros for hand-written programs:

```c
#define SAMVM_RR(op, dst, a, b)        /* register-register form */
    ((SamVMInst){(op), (dst), (a), (b), {0}})
#define SAMVM_RI(op, dst, a, imm)      /* register-immediate form */
    ((SamVMInst){(op), (dst), (a), 0, {.f = (imm)})
#define SAMVM_J(op, test_reg, target)  /* jump form */
    ((SamVMInst){(op), 0, (test_reg), 0, {.u = (target)})
```

Register-index helpers (banks are config-dependent):

```c
uint8_t samvm_reg_in (const SamVM *vm, uint8_t i);  /* i-th input  */
uint8_t samvm_reg_calc(const SamVM *vm, uint8_t i); /* i-th calc   */
uint8_t samvm_reg_out(const SamVM *vm, uint8_t i);  /* i-th output */
```

### 4.3 Opcode Set

36 opcodes. `r[x]` denotes the register bank value; `aux.f` the immediate.
Comparisons write `1.0f` (true) or `0.0f` (false).

| Group | Op | Semantics |
|---|---|---|
| Misc | `NOP` | no effect |
| | `HALT` | stop; run ends with `halted = true` |
| Move | `MOV d, s1` | `r[d] = r[s1]` |
| | `MOVI d, f` | `r[d] = aux.f` |
| Arith RR | `ADD/SUB/MUL/DIV d, s1, s2` | `r[d] = r[s1] op r[s2]` (DIV protected, §4.4) |
| Arith RI | `ADD_I/SUB_I/MUL_I/DIV_I d, s1, f` | `r[d] = r[s1] op aux.f` |
| Arith misc | `MIN/MAX d, s1, s2` | `fminf / fmaxf` |
| | `ABS d, s1` | `fabsf(r[s1])` |
| | `NEG d, s1` | `-r[s1]` |
| | `SQRT d, s1` | protected, §4.4 |
| Math | `SIN/COS d, s1` | `sinf/cosf(r[s1])` |
| | `EXP d, s1` | `expf(r[s1])`, result sanitized |
| | `LOG d, s1` | protected, §4.4 |
| Cmp RR | `LT/LE/GT/GE/EQ/NE d, s1, s2` | `r[d] = (r[s1] cmp r[s2]) ? 1 : 0` |
| Cmp RI | `LT_I/LE_I/GT_I/GE_I/EQ_I/NE_I d, s1, f` | vs `aux.f` |
| Branch | `JMP t` | `pc = aux.u` |
| | `JZ s1, t` | jump if `r[s1] == 0.0f` |
| | `JNZ s1, t` | jump if `r[s1] != 0.0f` |

`SAMVM_OP_COUNT` terminates the enum. Field usage per op is published as
metadata (§4.9) — that table is the single source of truth for the
disassembler, the validator, and the user's mutation operators.

### 4.4 Numeric Protections & Sanitization (normative)

Every value written to any register — including `MOV` and loads of inputs —
passes one sanitization step; every "protected" op replaces its guard case
with `0.0f` **before** sanitization:

```
sanitize(x):   if x is NaN        -> 0.0f
               if x == +inf       -> +FLT_MAX
               if x == -inf       -> -FLT_MAX
               else x

DIV/DIV_I:     if divisor == 0.0f or divisor is NaN -> result 0.0f (else quotient, then sanitize)
SQRT:          if x < 0 -> 0.0f                         (else sqrtf)
LOG:           if x <= 0 -> 0.0f                         (else logf)
EXP:           expf, then sanitize (overflow clamps to FLT_MAX)
```

Invariants that follow (and are asserted by the fuzz suite):

- All registers are finite at all times — NaN/±Inf can never be observed.
- Input values are sanitized on load, so the invariant holds even for
  garbage caller data.
- All cases are exactly reproducible in C/OpenCL by emitting the same guards.

**Build requirement:** samvm must never be compiled with `-ffast-math` (or
per-file equivalents) — the NaN/Inf guards above would be optimized away,
voiding every invariant in this section. `src/samvm_run.c` raises `#error`
when `__FAST_MATH__` is defined; the same guard goes into the future
translator sources (§6).

### 4.5 Register Banks

| Index range | Bank | Init | Write rule |
|---|---|---|---|
| `[0, n_in)` | input | caller values (sanitized) | **ignored** — instruction executes as effective NOP |
| `[n_in, n_in+n_calc)` | calc | `0.0f` | allowed |
| `[n_in+n_calc, +n_out)` | output | `0.0f` | allowed; caller reads after run |

Write-to-input-is-ignored (rather than an error) keeps mutated genomes
running: an intron that targets an input register is harmless, deterministic,
and cheap. Reads of any register are always defined.

### 4.6 Execution Semantics

```c
SamVMError samvm_run(const SamVM *vm, const SamVMProgram *prog,
                     const float *inputs, uint8_t n_inputs,
                     float *outputs, uint8_t n_outputs,
                     SamVMRunStats *stats /* NULL ok */);

typedef struct {
    uint64_t steps;    /* instructions executed (incl. NOP/effective-NOP) */
    uint32_t pc_end;   /* pc at stop                                       */
    bool halted;       /* stopped via HALT or pc == length                 */
    bool budget_hit;   /* stopped because steps == max_steps               */
} SamVMRunStats;
```

Run loop pseudocode (normative):

```
regs = sanitize(inputs) ++ zeros(calc) ++ zeros(out)
pc = 0; steps = 0
while pc < length and steps < max_steps:
    inst = code[pc]; steps++
    op = inst.op
    if op >= OP_COUNT:                          pc++; continue      # junk -> NOP
    if any used register field >= n_total:       pc++; continue      # junk -> NOP
    if op is jump and aux.u >= length:          break                # junk -> halt
    execute inst per §4.3-§4.5 (writes sanitized per §4.4)
    if op == HALT: halted = true; break
    if op is jump and taken: pc = aux.u else pc++
halted |= (pc == length)
copy out regs to caller buffer
```

Tolerant-decode rules (junk → NOP, junk target → halt) exist so arbitrary
mutated genomes always run deterministically; `samvm_program_validate`
(§4.8) is the strict mode for hand-written code. Count mismatches between
the run call and the config return `SAMVM_ERROR_INVALID_ARG`. Budget
exhaustion sets `budget_hit` and **returns success** — for LGP it is an
ordinary outcome (the outputs are the partial results).

**Determinism contract:** identical `(vm config, program, inputs)` produce
identical outputs and stats for a given samvm version. Strength splits by
opcode group: every group except `MATH` is **bitwise**-identical across
platforms and compilers (assuming default IEEE rounding and SSE2-class
scalar f32, true on all supported 64-bit targets). The `MATH` group
(`sinf/cosf/expf/logf`) is libm-hosted and varies by a few ULP across
glibc/musl/macOS/Windows — bitwise-identical within one platform+build,
within a documented ULP tolerance everywhere else. The regression corpus
(§9.4) enforces the bitwise part across releases using only
bitwise-stable opcode groups; `MATH` is pinned by tolerance-based unit
tests (§9.1).

### 4.7 Program Construction & Surgery

```c
typedef struct {
    SamVmInst *code;
    uint32_t   length;
    uint32_t   capacity;
} SamVMProgram;

SamVMError samvm_program_create (Samrena *a, uint32_t capacity, SamVMProgram **out);
SamVMError samvm_program_append (Samrena *a, SamVMProgram *p, SamVMInst inst,
                                 uint32_t *index_out /* NULL ok */);
uint32_t   samvm_program_label  (const SamVMProgram *p);       /* == p->length */

SamVMError samvm_program_patch  (SamVMProgram *p, uint32_t site, uint32_t target);
SamVMError samvm_program_copy   (Samrena *a, const SamVMProgram *src,
                                 SamVMProgram **out);
SamVMError samvm_program_insert (Samrena *a, SamVMProgram *dst, uint32_t at,
                                 const SamVMProgram *src, uint32_t src_at, uint32_t len);
SamVMError samvm_program_cut    (SamVMProgram *p, uint32_t at, uint32_t len);

uint64_t   samvm_program_hash   (const SamVMProgram *p);
bool       samvm_program_equal  (const SamVMProgram *a, const SamVMProgram *b);
```

- `append` grows capacity arena-style (new allocation + copy); length may
  not exceed `SAMVM_MAX_PROGRAM_LEN`.
- `patch` rewrites the jump at `site` to target `target`; errors with
  `SAMVM_ERROR_INVALID_OP` if `code[site]` is not a jump. `label` +
  `patch` cover both directions:

```c
/* backward */                                       /* forward */
uint32_t loop = samvm_program_label(p);              uint32_t site;
samvm_program_append(a, p, SAMVM_J(SAMVM_OP_JNZ, c0, 0), &site);
...emit body...                                      ...emit body...
samvm_program_patch(p, site, loop);                  samvm_program_patch(p, site, samvm_program_label(p));
```

- `insert` shifts the tail (pure insert — no overwrite); `cut` removes a
  range. Together with `copy` these are the crossover primitives; choosing
  points/rates is the LGP layer's job.
- `hash`/`equal` serve population management: fitness caching keyed by
  genome hash and duplicate elimination. The hash is Murmur3 (samdata
  `SamHash`) over the canonical LE field encoding (same as §4.12 pack), so
  it is stable across platforms. `equal` is bitwise on length + code (`aux`
  compared as u32 bits — `0.0f` and `-0.0f` are different genomes, which is
  what genetics dedup wants).

### 4.8 Validation

```c
SamVMError samvm_program_validate(const SamVM *vm, const SamVMProgram *p,
                                  uint32_t *bad_index /* NULL ok */);
```

Strict checks (for hand-written and pre-screened programs): opcode
`< OP_COUNT`; every register field used by the op within `n_total`; `dst`
not in the input bank; jump targets `< length`. Returns
`SAMVM_ERROR_INVALID_OP / _REGISTER / _TARGET` with the offending index.
The interpreter never requires validation — both layers share the same
`SamVMOpInfo` metadata so they cannot disagree.

### 4.9 Opcode Metadata

```c
typedef enum { SAMVM_CAT_MISC, SAMVM_CAT_MOVE, SAMVM_CAT_ARITH, SAMVM_CAT_MATH,
               SAMVM_CAT_CMP, SAMVM_CAT_BRANCH } SamVMOpCategory;

typedef struct {
    const char    *name;     /* "ADD_I" */
    uint8_t        category; /* SamVMOpCategory */
    uint8_t        uses;     /* SAMVM_USE_DST / SRC1 / SRC2_REG / IMM / TARGET bits */
} SamVMOpInfo;

const SamVMOpInfo *samvm_op_info(SamVMOp op);   /* NULL past OP_COUNT */
const char        *samvm_op_name(SamVMOp op);   /* "NOP" etc., "???" past OP_COUNT */
```

This table is what makes the user's mutation operators trivial: pick a
category, then randomize only the fields the op actually uses.

### 4.10 Random Program Generation

```c
SamVMError samvm_program_random(Samrena *a, const SamVM *vm, SamRng *rng,
                                uint32_t length, SamVMProgram **out);
```

Emits `length` instructions drawn uniformly from all opcodes, with valid
register fields (per `SamVMOpInfo`) and jump targets uniform in
`[0, length)` — i.e., always passes `samvm_program_validate`. Biased
distributions (opcode weighting, constant perturbation, effective-register
initialization) are LGP-layer concerns built on top of `SamRng` +
`samvm_op_info`. Deterministic under the RNG seed — this function is what
the fuzz suite and the seeded part of the regression corpus are built on.

### 4.11 Disassembly

```c
SamVMError samvm_disassemble(Samrena *a, const SamVM *vm,
                             const SamVMProgram *p, char **out);
```

One instruction per line, bank-aware register names (`i0..`, `c0..`, `o0..`),
numeric jump targets:

```
0000  MOVI   c0, 4.000000e+00
0001  ADD    o0, o0, i0
0002  SUB_I  c0, c0, 1.000000e+00
0003  JNZ    c0, 0001
0004  HALT
```

### 4.12 Serialization

```c
SamVMError samvm_program_pack  (Samrena *a, const SamVM *vm, const SamVMProgram *p,
                                void **buf, size_t *size);
SamVMError samvm_program_unpack(Samrena *a, const void *buf, size_t size,
                                SamVM **vm_out, SamVMProgram **prog_out);
```

Little-endian, self-contained (config + code) so a `.samvm` blob runs
anywhere:

```
offset  size  field
0       4     magic "SVM1"
4       4     u32 version (= 1)
8       4     u32 n_inputs
12      4     u32 n_calc
16      4     u32 n_outputs
20      4     u32 max_steps
24      4     u32 length
28      4     reserved (0)
32      8*N   instructions: op, dst, src1, src2, aux (u32 bit pattern), each 1/1/1/1/4 bytes
```

Byte-swapped on big-endian hosts. Bad magic / version / truncated /
oversized length → `SAMVM_ERROR_BAD_FORMAT`. This is the archive format for
champions and the container format of the regression corpus.

### 4.13 Errors

```c
typedef enum {
    SAMVM_ERROR_NONE = 0,
    SAMVM_ERROR_INVALID_ARG,     /* NULL pointers, count mismatch, bad config */
    SAMVM_ERROR_ALLOC,           /* arena allocation failed */
    SAMVM_ERROR_CAPACITY,        /* append/insert beyond SAMVM_MAX_PROGRAM_LEN */
    SAMVM_ERROR_INVALID_OP,      /* unknown opcode / patch site is not a jump */
    SAMVM_ERROR_INVALID_REGISTER,/* validate: field out of range or dst in input bank */
    SAMVM_ERROR_INVALID_TARGET,  /* validate: jump target >= length */
    SAMVM_ERROR_BAD_FORMAT       /* unpack: corrupt blob */
} SamVMError;

const char *samvm_error_string(SamVMError err);
```

**Deviation from the samcl last-error pattern:** errors are return-code
only, no per-handle detail buffer, because `samvm_run` must stay re-entrant
and `const` (§5). Rich context (which instruction is bad) flows through the
`bad_index` out-params.

## 5. Thread-Safety & Re-entrancy

- A `SamVM` handle is immutable after create: `samvm_run`, `validate`,
  `disassemble`, `pack` all take `const SamVM *`.
- Run state (register file ≤ 1 KB, pc, steps) lives on `samvm_run`'s stack;
  nothing is written to the handle or the program.
- Therefore: one shared VM + program may be run concurrently from multiple
  threads given distinct `inputs`/`outputs`/`stats` buffers — population
  evaluation parallelizes with zero samvm changes.
- Program *construction* functions (`append`, `insert`, `cut`, `patch`) are
  not synchronized; each thread builds in its own program (or the LGP layer
  locks).

## 6. Compilation Guiding Star (out of v1, shaping v1)

Future API sketch (do not build now):

```c
char *samvm_translate_c       (Samrena *a, const SamVM *vm, const SamVMProgram *p,
                               const char *fn_name);   /* -> compilable C source */
char *samvm_translate_opencl  (Samrena *a, const SamVM *vm, const SamVMProgram *p,
                               const char *fn_name);   /* -> OpenCL C source */
```

Translator shape kept possible by v1 invariants (§2.2): emit one flat
function — registers as a local `float r[N]`, each instruction as
`L<k>: r[d] = <expr>;` at a single scope, jumps as `goto L<t>`, backward
jumps wrapped with a `steps`-counter guard reproducing the budget. No
structured control flow is ever emitted, so `goto` legality is trivial in
both C and OpenCL C. The regression corpus doubles as the parity suite:
interpreter vs translated outputs compared bitwise (C) and with documented
tolerance (OpenCL).

Honest determinism caveat, recorded now so it surprises nobody later:
OpenCL guarantees correctly-rounded f32 add/sub/mul/div/sqrt only in the
absence of fast-math flags, and `sinf/cosf/expf/logf` carry ULP tolerances
that vary by vendor — bitwise CPU↔GPU parity is achievable for the
arithmetic ops but **not promised** for transcendentals — and translated C
has the mirror-image issue, since host libms differ across platforms for
the same ops (§4.6). The future parity
work must accept ULP-level tolerance for `MATH` ops in both directions
(shipping canonical implementations remains the escape hatch if a problem
ever demands bitwise parity). This is the single known gap between the
guiding star and strict determinism.

## 7. samdata Impact

**No samdata additions required for v1.** Audit of needs:

| Need | Covered by |
|---|---|
| Random numbers (generation, tests) | `SamRng` — existing |
| Growable instruction array | plain `SamVMProgram` struct (§3.5 decision) |
| Set of register indices (future effective-instruction / liveness analysis in the LGP layer) | `SamSet` — existing |
| Genome hashing (duplicate elimination, fitness caching) | `SamHash` (Murmur3) — existing |

If implementation surfaces a genuinely generic structure (e.g., a bitmap
type for liveness marking that `SamSet` handles awkwardly), it goes to
samdata per the standing rule — flagged as an open question for review, not
silently embedded in samvm.

## 8. Out of Scope for v1

| Feature | Reason |
|---|---|
| C / OpenCL translators | Guiding star only (§6); interpreter suffices for research-phase LGP |
| Batch/parallel run driver (`samvm_run_many`) | Re-entrancy already lets the LGP layer parallelize; a driver would bake in policy |
| f64 or integer register variants | f32 decided (§3.2); variants are a v2 flavor if a real need appears |
| Constant-pool register bank | LGP-layer sugar on top of `MOVI` (§3.4) |
| Structured loop ops | Raw jumps + budget decided (§3.3) |
| Effective-instruction / intron analysis | LGP layer; `SamVMOpInfo` gives it what it needs |
| Threaded dispatch / computed-goto interpreter | Plain switch first; perf test (§9.3) guards regressions; optimize when measured necessary |
| Watchpoints, breakpoints, single-step debugging API | Disassembler + stats cover v1; add when hand-debugging actually hurts |
| Fitness hooks / observation callbacks in `samvm_run` | Callbacks would break the re-entrancy and translation stories |

## 9. Test Plan

Plain C, `assert()` + `printf()`, one binary per file, registered with CTest
(repo convention). `m` linked for `sqrtf`-family references in expectations.

### 9.1 Unit — `test/samvm_test.c` → ctest `samvm_unit`

| Test | Description |
|---|---|
| `test_config_default` | defaults are 4 / 8 / 2 / 1024 |
| `test_create_destroy` | create/destroy/destroy(NULL); bad config rejected (0 regs, 256 regs, 0 steps) |
| `test_inst_size` | `sizeof(SamVMInst) == 8` |
| `test_op_info_table` | every op `< OP_COUNT` has non-NULL name; `uses` bits match §4.3; past-count returns NULL |
| `test_error_strings` | non-NULL string for every enum value |
| `test_program_append_growth` | append past capacity; content preserved; index_out correct |
| `test_program_label_patch` | forward and backward branch authoring (§4.7 examples) |
| `test_program_copy_insert_cut` | segment surgery composes; insert shifts tail; cut shrinks; crossover round-trip A×B |
| `test_program_hash_equal` | hash deterministic and stable; equal programs hash equal; single-field change alters hash; `equal` bitwise incl. `-0.0f` vs `0.0f` |
| `test_max_program_len` | append beyond `SAMVM_MAX_PROGRAM_LEN` → `SAMVM_ERROR_CAPACITY` |
| `test_ops_arith` | every RR/RI arith op: known inputs → expected outputs |
| `test_ops_protected` | DIV by 0 / by NaN-input → 0; SQRT(-x) → 0; LOG(0)/LOG(-x) → 0; EXP(large) clamps to FLT_MAX |
| `test_ops_sanitize` | MUL overflow → FLT_MAX; NEG/SUB inf-clamp; inputs sanitized on load |
| `test_ops_min_max_abs_neg_math` | exact expectations for MIN/MAX/ABS/NEG; `MATH` (SIN/COS/EXP/LOG) checked against libc within 4 ULP |
| `test_ops_compare` | all 12 cmp ops, taken and not-taken, produces exactly 1.0f/0.0f |
| `test_register_banks` | write to input ignored (effective NOP); calc init 0; output readable after run |
| `test_branch_jmp_jz_jnz` | forward, backward, taken, not-taken |
| `test_loop_countdown` | the §4.11 disassembly program computes the loop result exactly |
| `test_budget_exhaustion` | infinite loop program stops at `max_steps`, `budget_hit`, outputs finite |
| `test_fall_off_end` | pc == length → `halted`, no error |
| `test_tolerant_decode` | junk opcode / junk register → NOP; junk target → halt; run stays deterministic |
| `test_stats` | steps/pc_end/halted/budget_hit correct across halt, budget, and fall-off cases |
| `test_determinism` | same run twice → identical outputs and stats, bitwise |
| `test_validate` | catches bad op, bad register, dst-in-input, bad target; `bad_index` points at offender; random programs always pass |
| `test_random_program` | requested length honored; deterministic under seed; validate-clean |
| `test_pack_unpack_roundtrip` | config + code + both `aux` union uses survive; run outputs identical |
| `test_pack_rejects` | bad magic, bad version, truncated, oversized length → `BAD_FORMAT` |
| `test_disassemble` | §4.11 program renders exactly the documented text |
| `test_null_safety` | NULL handles/pointers → `INVALID_ARG` on every public function |

### 9.2 Fuzz — `test/samvm_fuzz.c` → ctest `samvm_fuzz`

Fixed seed (overridable via argv for local hunting). 10,000 random programs
(seeded `SamRng`, lengths 1–256) × random inputs, asserting the §4.4/§4.6
invariants:

| Property | Assertion |
|---|---|
| Termination | run returns `NONE` and `steps <= max_steps` |
| Finiteness | every output finite (no NaN/±Inf) |
| Determinism | immediate re-run bitwise-identical |
| Validity of generator | `validate(random program)` passes |
| Junk resilience | for a sample: random byte-corruptions of packed blobs either unpack-fail cleanly or run under the same invariants |

### 9.3 Performance — `test/samvm_perf.c` → ctest `samvm_perf`

Runs one representative program (mixed arith + loop) for a fixed step count,
prints instructions/second, asserts a generous floor (**≥ 1M inst/s**, so it
passes under Debug and the Valgrind build type) to catch dispatch
regressions. Not a benchmark harness — optimizing the interpreter is
explicitly out of scope for v1.

### 9.4 Regression — `test/samvm_regress.c` → ctest `samvm_regress`

Golden corpus at `test/cases/`, one directory per case:

```
cases/0007-countdown-sum/
├── prog.samvm      # packed program + config (§4.12)
├── inputs.f32      # raw LE f32 inputs
├── expected.f32    # raw LE f32 outputs (the golden bits)
└── flags.txt       # expected halted / budget_hit / steps
```

The runner enumerates the directory (POSIX `dirent`; case path injected via
`SAMVM_CASE_DIR` compile definition), unpacks, runs, and compares outputs
**bitwise** (`memcmp`) — determinism is the contract, so bitwise is the
correct strength. `--bless` argv regenerates `expected.f32`/`flags.txt`
(dev tool, never run by CTest).

Corpus seeding and growth policy:

1. Curated hand-written cases covering every opcode group, every
   protection, loops, budget exhaustion, and tolerant-decode rules — using
   only bitwise-stable groups (all but `MATH`, whose results are
   libm-hosted, §4.6) so the golden bits hold on every platform. `MATH`
   semantics are pinned by the ULP-tolerance unit tests (§9.1) instead.
2. 100 seeded random programs committed as binary cases (generator +
   interpreter changes both get caught).
3. **Every fixed interpreter bug adds a case** — the corpus is the
   regression suite's memory.
4. The same corpus is the future translator-parity suite (§6) — one
   investment, two eras.

## 10. CMake

```cmake
ptah_add_library(samvm
    INSTALL
    SOURCES
        src/samvm.c
        src/samvm_program.c
        src/samvm_run.c
        src/samvm_codec.c
    PUBLIC_HEADERS
        include/samvm.h
    DEPENDENCIES
        samrena
        samdata
)

if(BUILD_TESTING)
    ptah_add_executable(samvm_test
        SOURCES test/samvm_test.c
        DEPENDENCIES samvm m)
    add_test(NAME samvm_unit COMMAND samvm_test)

    ptah_add_executable(samvm_fuzz
        SOURCES test/samvm_fuzz.c
        DEPENDENCIES samvm m)
    add_test(NAME samvm_fuzz COMMAND samvm_fuzz)

    ptah_add_executable(samvm_perf
        SOURCES test/samvm_perf.c
        DEPENDENCIES samvm m)
    add_test(NAME samvm_perf COMMAND samvm_perf)

    ptah_add_executable(samvm_regress
        SOURCES test/samvm_regress.c
        DEPENDENCIES samvm m)
    target_compile_definitions(samvm_regress PRIVATE
        SAMVM_CASE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/cases")
    add_test(NAME samvm_regress COMMAND samvm_regress)
endif()
```

No `ptahConfig.cmake.in` change needed — samrena/samdata are internal
`ptah::` exports already handled by the existing machinery; samvm exports
as `ptah::samvm`.

## 11. Documentation Updates (at implementation time)

- `PROJECTS.md`: new `samvm` entry (status `in-development`, public header
  `samvm.h`, depends on samrena, samdata, one-paragraph description) and a
  `samrena → samdata → samvm` line in the dependency graph.
- `apps/examples/` list: add the new example (below).
- AGENTS.md: no change — samvm follows the existing single-umbrella-header
  rule.

### Example app — `apps/examples/samvm_example/`

Small `main.c` that: builds the §4.11 countdown-loop program with the emit
macros, prints its disassembly, runs it on sample inputs, prints outputs +
stats, then generates a seeded random program and runs it — a compact
ergonomics demonstration and a smoke test of the intended LGP-facing
workflow.

## 12. Implementation Order

Tracked in samtasker (project `PTAH`); each ticket goes `in-progress` →
commit & push → `done`, build green at every step.

| # | Ticket | Depends on |
|---|---|---|
| 1 | Public header: config, errors, opcode enum + metadata table, emit macros, `samvm.c` lifecycle; unit tests for config/table/size | — |
| 2 | Program construction: create/append/label/patch/copy/insert/cut + hash/equal + tests | 1 |
| 3 | Interpreter `samvm_run.c`: full §4.6 semantics; op/protection/branch/budget unit tests | 2 |
| 4 | Validate + `samvm_program_random` + fuzz suite | 3 |
| 5 | Codec: pack/unpack + disassemble + tests | 3 |
| 6 | Regression corpus + runner + `--bless` + seed cases (curated + 100 random) | 4, 5 |
| 7 | Perf smoke test + example app + `PROJECTS.md` | 3, 5 |

## 13. Resolved Decisions (confirmed in review)

All four originally-open questions were confirmed by the user:

| # | Question | Resolution |
|---|---|---|
| Q1 | f32 vs f64 registers | **f32 confirmed** (§3.2) |
| Q2 | Default banks 4 in / 8 calc / 2 out, 1024-step budget | **Confirmed as defaults** — everything lives in `SamVMConfig`, so tuning is a one-line change |
| Q3 | Immediate variants for arith + compares only | **Confirmed** (§3.4) |
| Q4 | Writes to input registers silently ignored | **Confirmed** (§4.5) |

Post-review hardening added in the same revision: the determinism strength
split for libm-hosted `MATH` ops (D8, §4.6 — the original contract
over-promised bitwise parity across libms), the `-ffast-math` compile guard
(D9, §4.4), program hash/equal helpers (D10, §4.7), and header version
macros (§4.1).
