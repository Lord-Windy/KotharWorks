# Ironwood Wilds — C11 (`ptah`) → Java 25 LTS (`kotharworks`) Bridge Notes

**Date:** 2026-11-20  
**Status:** Stage 1 complete; Stage 2 planning next  
**Baseline Architecture:** [`BigPlanningReview/carbon/README.md`](./BigPlanningReview/carbon/README.md)

---

## 1. Why KotharWorks Exists

Ironwood Wilds is an online, persistent, multi-household frontier simulation where most week-to-week implementation will be delegated to AI coding agents while Fridays are reserved for design, population hypothesis experiments, and architectural review.

Moving the project into **KotharWorks** (`com.kotharworks`, pure Java 25 LTS + Maven) keeps the modular `libs/` + `apps/` monorepo workflow from Ptah while eliminating cross-arena pointer hazards, custom C network-parser exposure, and borrow-checker/async friction:
- **Zero-bloat runtime:** Pure Java 25 LTS (`-XX:+UseCompactObjectHeaders`), Virtual Threads, zero Spring Boot, zero Hibernate/JPA.
- **Compiler-enforced boundaries:** Maven modules (`libs/procgen` → `libs/wilds` → `apps/wilds-server`) prevent I/O, SQL, or HTTP imports from ever leaking into pure simulation code.
- **Fast AI feedback loop:** `-Xlint:all -Werror`, exhaustive `sealed interface` switches, immutable `record`s, and ~1s Maven reactor test runs (`<0.5s` with `mvnd`).

---

## 2. Direct Pattern Mapping (Carbon C11 Plan → Java 25 LTS)

Every core decision in [`BigPlanningReview/carbon/`](./BigPlanningReview/carbon/README.md) carries straight over without OOP pointer spaghetti:

| Carbon C11 Concept | Java 25 LTS Equivalent in `kotharworks` |
|---|---|
| `SamWildsItemDef` / `RuleDef` in `rules_arena` | Immutable `record` catalogs loaded once into unmodifiable lists/maps (`Map<ItemDefId, ItemDef>`) |
| `SamWildsId` (`index` + `generation`) & Arena Pools | Value-typed ID `record`s (`HouseholdId`, `CharacterId`, `SiteId`) + flat indexed tables (`ArrayList` / `Long2Object` maps); **zero mutable cross-entity object references** |
| `SamWildsCommand` tagged union + function-pointer vtable | `sealed interface WildsCommand` of immutable `record`s + exhaustive `switch` / handler registry |
| `Strategy` (`fn ptrs + void *ctx`) | `@FunctionalInterface`s / clean policy interfaces (`ControllerPolicy`, `RaidDoctrine`, `WorldStorePort`) |
| `SamWildsEventHeap` (`due_ms`, `seq`) | `PriorityQueue<ScheduledEvent>` ordered by `Comparator.comparingLong(ScheduledEvent::dueMs).thenComparingLong(ScheduledEvent::seq)` |
| Fogged `samwilds_observe` into `conn_arena` | `WildsObserver.observeHousehold(world, householdId)` returning immutable `HouseholdView` `record` trees (blindness enforced by construction) |
| `samplex` + `samhttp` + `samhtml` | Lightweight embedded HTTP/WS server on **Virtual Threads** (e.g. Javalin or JDK `HttpServer`) + type-safe escaped HTML builder/templates + Jackson JSON |
| `libpq` over `auth` / `wilds_meta` / `wilds` schemas | **HikariCP + JDBI 3** (or plain JDBC) + Flyway/SQL migrations over the exact same 3 PostgreSQL schemas (`auth`, `wilds_meta`, `wilds`) |
| `samwilds-cli` & `samwilds-bot` | `apps/wilds-cli` & `apps/wilds-bot` using JDK's built-in `java.net.http.HttpClient` (no engine dependency — proves the public protocol) |
| Future `samvm` / `samgenai` integration | Either external bots over TCP/REST or a ~400-LOC pure Java `libs/vm` + `libs/genai` port running headless episodes in parallel streams |

---

## 3. Stage 2 Planning Agenda (Tomorrow)

1. **Synthesize the best ideas across the 6 review packs** (`carbon` baseline + any sharp additions from `fable`, `kimi`, `astra`, `glm`, `gpt-6.2-astra` — e.g., snapshot+journal vs. dirty-table persistence, idempotency keys on bot commands, starter-region validation).
2. **Pin the tiny library stack** for `apps/wilds-server` (e.g., Javalin vs. Helidon Nima, JDBI 3 + PostgreSQL JDBC, Jackson, HTML rendering choice).
3. **Lock down the Vertical Slice ("The Timber & Bog-Iron Charter") spec** (`libs/procgen`, `libs/wilds`, `apps/wilds-server`, `apps/wilds-bot`) and cut the dependency-ordered `st --project KOTHARWORKS` tickets.
