# Ticket 05 — Headless CLI & WAL Replay Rig (`apps/wilds-headless`)

- **`st` Key:** `aee4bb00` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Backlog *(auto-promotes when `d8ed0e93` completes)*
- **Depends on:** Ticket `04` (`d8ed0e93`)
- **Modules touched:** Root `pom.xml`, `PROJECTS.md`, `apps/wilds-headless` *(new module)*

---

## 1. Objective

Create `apps/wilds-headless` (`com.kotharworks:wilds-headless`), the zero-server command-line harness that:
1. Fast-forwards pure `WildsEngine` runs in milliseconds via `advanceToNextEvent()` (proving zero-idle-cost simulation and laying the exact runner foundation for future batch experiments).
2. Replays `.jsonl` Write-Ahead Logs (`WalRecord`: `CLOCK_ADVANCE` + `WorldCommand` entries) exported from `wilds-server` and verifies **bit-for-bit `WorldHash` parity** (plus version-triple compatibility).
3. Inspects seeded bootstrap topologies and `glance`/`look`/`examine` stub output from the terminal.

---

## 2. Explicit Non-Goals

- Zero HTTP clients, zero bot AI (`Doctrine`), zero database drivers — `apps/wilds-headless` depends **only** on `com.kotharworks:wilds`!

---

## 3. Deliverables & Structure

### 3.1 Maven Module Setup
- Create `apps/wilds-headless/pom.xml` inheriting from `kotharworks-parent`, depending strictly on `com.kotharworks:wilds` (and `junit-jupiter` in `test` scope), with `exec-maven-plugin` configured for `com.kotharworks.wilds.headless.HeadlessMain`.
- Register `<module>apps/wilds-headless</module>` and a `com.kotharworks:wilds-headless` `<dependencyManagement>` entry in root `pom.xml` (so Ticket `09`'s `test`-scope parity check in `apps/wilds-server` resolves cleanly), and document `wilds-headless` in `PROJECTS.md`.
- Note on CLI invocation via Maven: pass subcommand arguments using `-Dexec.args="inspect --seed 0x49524F4E"` (never `-- inspect ...`, which Maven interprets as lifecycle phases).

### 3.2 `.jsonl` Stream Format & Replay Runner (`com.kotharworks.wilds.headless`)
- **Seed Argument Parsing:** Support both signed decimal `long`s and full 64-bit unsigned `0x...`/`0X...` hex strings via `Long.parseUnsignedLong(s.substring(2), 16)` (avoiding `Long.decode` overflow on 16-hex-digit hashes with bit 63 set).
- **Optional Header Line (`meta`):** First line may carry `{"type":"WAL_HEADER","worldSeed":4805198,"rulesVersion":1,"engineVersion":1}`:
  - If `rulesVersion != RuleRegistry.WILDS_RULES_VERSION` or `engineVersion != WildsEngine.API_VERSION`, fail fast with exit code `2` and a clear version-mismatch diagnostic!
- **WAL Body Lines:** One `WalRecord` JSON object per line (`CLOCK_ADVANCE` or `COMMAND` from Ticket `04`):
  - Steps `engine.advanceTo(record.admittedMs())`, applies `WorldCommand` (asserting it succeeds without Phase-1 error — since `command_wal` only records admissible commands!), and if `record.worldHash() != null`, asserts `engine.worldHash() == record.worldHash()` inline!
- **CLI Subcommands (`HeadlessMain`):**
  1. `run --seed <long|0xHex> [--owners <n>] [--until-ms <ms>] [--out-wal <file>]`:
     - Registers `<n>` test owners, queues sample `Travel`/`Wait` loops, fast-forwards via `advanceToNextEvent()` up to `--until-ms`, optionally writes the `.jsonl` WAL file, and prints `logicalMs`, `eventsEmitted`, and `worldHash=0x<16-hex>`.
  2. `replay --seed <long|0xHex> --wal <file> [--to-ms <ms>] [--hash]`:
     - Replays `<file>` deterministically (stopping at `--to-ms` if given) and prints `worldHash=0x<16-hex>` (`0` exit code on success, non-zero on corrupted JSON, inadmissible command, version mismatch, or inline `worldHash` mismatch).
  3. `inspect --seed <long|0xHex>`:
     - Prints the seeded `WorldTopologyBootstrap` place graph, edge durations, and `GLANCE`/`LOOK`/`EXAMINE` descriptions.

---

## 4. Tests & Acceptance Criteria

- **`HeadlessCliTest`:**
  - Invokes `HeadlessMain.execute(String[] args, PrintWriter out, PrintWriter err)` in-process (no slow JVM subprocess spawning needed!):
    1. `run --seed 0x49524F4E --owners 4 --until-ms 86400000 --out-wal <tempFile>` simulates 24 logical hours in `< 100ms` and writes a valid `.jsonl` WAL.
    2. `replay --seed 0x49524F4E --wal <tempFile> --hash` outputs the **exact same `0x...` `WorldHash`** as `run`.
    3. Tampering with a single `admittedMs` or inline `worldHash` in `<tempFile>` causes `replay` to return non-zero exit status with the offending `walSeq` in `err`.
    4. Passing a mismatched `rulesVersion` in `WAL_HEADER` returns exit code `2`.
    5. `inspect --seed 0x49524F4E` prints all 3 bootstrap places and edge travel times cleanly.
