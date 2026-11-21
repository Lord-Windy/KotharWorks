# Ticket 01 — Workspace Cleanup, Seeded Streams & Determinism Architecture Gates

- **`st` Key:** `4e263046` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Ready
- **Depends on:** None
- **Modules touched:** Root `pom.xml`, `PROJECTS.md`, `libs/procgen` *(removed)*, `libs/wilds`, `apps/wilds-server` *(import update only)*

---

## 1. Objective

Clean the monorepo module graph so `libs/procgen` **does not exist** during base-structure development, establish domain-separated deterministic RNG primitives (`WorldSeed` + integer-first `SeededRng`) inside `libs/wilds`, and install an automated **JDK 25 Bytecode & Reflection Architecture Gate** (`WildsArchitectureGateTest`) that mechanically prevents nondeterminism from ever entering `libs/wilds`.

---

## 2. Explicit Non-Goals

- Do **not** implement noise functions (`OpenSimplex2S`, `PoissonDisc`), terrain passes, name grammars, personas, or prose catalogs.
- Do **not** add third-party architecture-testing frameworks (like ArchUnit/ASM) that add dependency bloat or lag JDK 25 classfile version `69.0`; use standard JDK 25 `java.lang.classfile` (`java.base`) + reflection.

---

## 3. Deliverables & Structure

### 3.1 Remove `libs/procgen` Module
1. Delete `libs/procgen/` directory (`pom.xml`, `WorldSeed.java`, `WorldSeedTest.java`, `target/`).
2. Remove `<module>libs/procgen</module>` and the `com.kotharworks:procgen` `<dependencyManagement>` entry from root `pom.xml`.
3. Remove the `com.kotharworks:procgen` dependency from `libs/wilds/pom.xml` (`libs/wilds` now has **zero runtime dependencies**).
4. Update `PROJECTS.md` to reflect that `libs/wilds` has zero runtime dependencies and remove the `procgen` entry.

### 3.2 Deterministic Seed & Domain-Separated Integer RNG (`com.kotharworks.wilds.seed`)
Create `libs/wilds/src/main/java/com/kotharworks/wilds/seed/`:
- **`WorldSeed.java`** (`record WorldSeed(long value)`):
  - `SeededRng stream(String domain, long a, long b)`: hashes `domain` UTF-8 bytes via explicit 64-bit **FNV-1a** (never `String.hashCode()`, guaranteeing cross-language/cross-tool stability) and mixes `(value, domainSalt, a, b)` through **SplitMix64** finalizers to return an isolated `SeededRng`.
  - Keep `long regionHash(int x, int y)` (delegating to or alongside `stream("region", x, y)`) and update the imports in `WildsEngine.java`, `WildsEngineTest.java`, and `apps/wilds-server/.../Main.java` so existing scaffold tests stay green.
- **`SeededRng.java`** (`final class SeededRng`):
  - Stateful SplitMix64 stream scoped strictly to a single local operation (never stored inside `WorldState`!).
  - Integer-only API (**zero `nextFloat()` / `nextDouble()` methods**):
    - `long nextLong()`
    - `int nextInt(int bound)` (rejects `bound <= 0`; unbiased rejection sampling)
    - `int nextRange(int minInclusive, int maxInclusive)` (rejects `minInclusive > maxInclusive`; computes `long span = (long) maxInclusive - (long) minInclusive + 1L` in `long` arithmetic to avoid `int` subtraction overflow)
    - `int nextPermille()` (`0 .. 999`)
    - `int nextBasisPoints()` (`0 .. 9_999`)
    - `<T> T pick(List<T> items)` (rejects empty list)

### 3.3 Automated Bytecode & Reflection Gate (`WildsArchitectureGateTest`)
Create `libs/wilds/src/test/java/com/kotharworks/wilds/WildsArchitectureGateTest.java` using `java.lang.classfile.ClassFile` (`java.base` in Java 25) + reflection:
1. **Forbidden API / Package Scan over `libs/wilds/target/classes/**/*.class`:**
   Inspect both `CodeModel` instructions (`InvokeInstruction`, `FieldAccessInstruction`, `NewObjectInstruction`) **and** constant-pool / `InvokeDynamicInstruction` bootstrap `MethodHandleEntry`s (so method references like `System::currentTimeMillis` or `Math::random` cannot slip past!) and fail with a clear `className + methodName + referencedSymbol` message if any production `.class` in `libs/wilds` references:
   - Wall-clock / timers: `java/lang/System.currentTimeMillis`, `java/lang/System.nanoTime`, `java/time/Instant`, `java/time/Clock`, `java/time/LocalDateTime`, `java/time/ZonedDateTime`, `java/util/Date`, `java/util/Timer`
   - Unseeded / standard RNG: `java/lang/Math.random`, `java/util/Random`, `java/util/concurrent/ThreadLocalRandom`, `java/security/SecureRandom`, `java/util/SplittableRandom`
   - I/O, Networking, SQL, Auth: `java/io/File`, `java/io/FileInputStream`, `java/io/FileOutputStream`, `java/io/FileReader`, `java/io/FileWriter`, `java/io/RandomAccessFile`, `java/nio/file/*`, `java/net/*`, `java/sql/*`, `javax/sql/*`, `com/kotharworks/auth/*` *(note: pure in-memory `java/security/MessageDigest` and `java/nio/ByteBuffer` are permitted for `WorldHash` in Ticket `03`)*.
2. **State Graph Type Walker (`assertPureDeterministicStateRoot(Class<?> rootType)`):**
   - Reusable recursive type inspector (invoked on `WildsEngine` now and wired to `WorldState` in Ticket `03`) asserting that no reachable state type is or inherits from:
     - `float`, `double`, `java.lang.Float`, `java.lang.Double`
     - `java.util.HashMap` *(including `LinkedHashMap`)*, `java.util.HashSet` *(including `LinkedHashSet`)*, `java.util.IdentityHashMap`, `java.util.concurrent.ConcurrentHashMap` (must use dense arrays, `EntityTable`, immutable `List`, or sorted `TreeMap`/`TreeSet`)
     - `com.kotharworks.wilds.seed.SeededRng` (RNG streams are transient per-call tools, never stored in state!).
   - **Traversal rules (critical for generic erasure & `sealed` hierarchies):**
     - For `com.kotharworks.*` classes/records/interfaces: traverse non-static `getDeclaredFields()` (`Field.getGenericType()`), array component types (`Class.componentType()` / `GenericArrayType`), **`ParameterizedType.getActualTypeArguments()`** (so `EntityTable<OwnerId, OwnerRow>` and `List<TaskSpec>` inspect `OwnerRow` and `TaskSpec` despite `Object[]` erasure!), and **`Class.getPermittedSubclasses()`** on `sealed` interfaces/classes.
     - For `java.*` JDK types: validate the `java.*` type itself against the banned set and recurse into any `ParameterizedType` type arguments, **without** calling `getDeclaredFields()` on JDK internal classes (avoiding false positives on private JDK internals inside `String`/`Class`/`Enum`).

---

## 4. Tests & Acceptance Criteria

- **`WorldSeedTest`:**
  - Pin exact golden `long` outputs for known `(seed, domain, a, b)` tuples so accidental hash formula edits fail immediately.
  - Prove domain isolation: `seed.stream("topology", 1, 2)` != `seed.stream("prose", 1, 2)`.
  - Prove coordinate order-independence: calling `stream` in forward vs. reverse order yields identical sequences.
- **`SeededRngTest`:**
  - Verify `nextInt(bound)` stays strictly within `[0, bound)`, throws `IllegalArgumentException` on `bound <= 0`, and distributes reasonably across buckets over 10,000 draws.
  - Verify `nextPermille()` stays in `[0, 999]` and `pick(list)` is deterministic for a fixed seed.
- **`WildsArchitectureGateTest`:**
  - Scans all compiled classes in `libs/wilds` and passes.
  - Includes synthetic test-only inner classes (excluded from the main scan, tested directly against the scanner helper) containing a `double` field, a `HashMap` field, and a `System.currentTimeMillis()` call — proving the gate **positively catches** each violation.
- `mvnd test` succeeds across the entire monorepo with zero warnings and zero `libs/procgen` directory remaining.
