# PROJECTS.md — KotharWorks Inventory

This file lists what lives in this monorepo. For coding standards, build
commands, and the `st` agent workflow, see [AGENTS.md](AGENTS.md).

**Status legend:**

- **stable** — public API settled, has tests, used by other projects
- **in-development** — actively being built; API may still change
- **prototype** — exploratory; expect rough edges
- **scaffold** — hello-world skeleton only; design in `planning/`

## Libraries (`libs/`)

### wilds — Ironwood Wilds simulation engine

**Status:** in-development · **Artifact:** `com.kotharworks:wilds` · **Package:**
`com.kotharworks.wilds` · **Depends on:** — (zero runtime dependencies)

Headless, deterministic Java 25 simulation core for **Ironwood Wilds**: pure
logical-millisecond clock, integer-only state, zero I/O, zero wall-clock calls,
command dispatch. Holds the `WildsEngine` deterministic engine facade
(`API_VERSION = 1`) pinned by `WildsEngineTest`, the domain-separated seeded RNG
primitives `WorldSeed` (FNV-1a + SplitMix64) and integer-only `SeededRng` in
`com.kotharworks.wilds.seed`,
and the JDK 25 `java.lang.classfile` bytecode & reflection purity gate
(`WildsArchitectureGateTest`). Ticket 02 adds the generational wire IDs
`OwnerId`/`ActorId`/`PlaceId` + `RuleId` with parse/round-trip validation in
`com.kotharworks.wilds.id`, and the dense `EntityTable`, integer `LazyGauge`,
and `(dueAtMs, seq)` `DueEventHeap` simulation structures in
`com.kotharworks.wilds.sim`. Ticket 03 adds the neutral `WorldState` schema and
entity rows (`OwnerRow`, `PlaceRow`, `ActorRow`, `TaskSpec`, `ActiveTask`) in
`com.kotharworks.wilds.state`, the compile-time `RuleRegistry` in
`com.kotharworks.wilds.rules`, the `WorldTopologyBootstrap`/`DescriptionComposer`
procgen strategy seams with deterministic stubs in `com.kotharworks.wilds.worldgen`,
and the zero-dependency `JsonCodec`, `SnapshotCodec`, and canonical SHA-256-based
`WorldHash` in `com.kotharworks.wilds.replay`. Ticket 04 completes the engine: the
`WildsEngine` facade with side-effect-free Phase-1 `WorldCommand` validation
(`ErrorCode`, `Phase1Result`, `Phase1Validator` in `com.kotharworks.wilds.command`),
the two-phase task mechanics and queue runner with O(1) heap-ticket cancellation
(`TaskMechanic`, `TaskQueueRunner`, `WaitMechanic`, `TravelMechanic` in
`com.kotharworks.wilds.task`), the outward-only `WorldEvent` outbox with monotonic
cursors (`com.kotharworks.wilds.event`), the deeply immutable `WorldView` and
fog-of-war `ObservationProjector` (`com.kotharworks.wilds.observe`), the
`CommandCodec`/`WalHeader`/`WalRecord` replay codecs, the `EventPayloadCodec` that owns the
at-rest `WorldEvent` format (stable lowercase kinds, variant payload plus embedded
`scopeOwner`, pinned byte-for-byte by `EventPayloadCodecTest`), and the golden replay +
5,000-step seeded fuzz harnesses. See
[`planning/README.md`](planning/README.md) and
[`planning/ironwood_wilds_planning/README.md`](planning/ironwood_wilds_planning/README.md).

### auth — reusable AuthN & HTTP security library

**Status:** in-development · **Artifact:** `com.kotharworks:auth` · **Package:**
`com.kotharworks.auth` · **Depends on:** `com.nimbusds:nimbus-jose-jwt` (only
external dependency; zero transitive runtime deps)

Game-agnostic AuthN & HTTP security library (Ticket 06). Sealed
`Principal` family (`HumanPrincipal` with `GlobalRole`s, `GlobalRole`-free
`ScopedTokenPrincipal`, `AnonymousPrincipal`) keeping automation keys out of
human identity space; `JwtClaimsVerifier` over Nimbus `DefaultJWTProcessor`
restricted to asymmetric `RS256`/`ES256` (hard-rejecting `alg:none`/`HS256`
key confusion), strict `iss`/`aud`/`sub`/`exp`/`nbf` checks (supporting
multi-audience Keycloak tokens) with 30 s skew and an injected
`java.time.Clock` for deterministic expiry; Keycloak
`realm_access`/`resource_access` role extraction with a `PLAYER` baseline;
loopback-fused `dev-local` mode (`LoopbackStartupFuse`, ephemeral ES256
`DevLocalJwtIssuer` with `/dev/login` persona switcher exercising the exact
production verifier offline) plus drop-in OIDC Authorization Code + PKCE
`S256` (`OidcJwksAuthenticator` cached JWKS with bounded retriever timeouts,
`OidcAuthorizationCodeClient` single-use TTL-gated `state`/`nonce`/
`code_verifier` and timeout-bounded `completeAuthorizationCode`); `iw_bot_`
scoped token codec (32-byte secrets, SHA-256 digests, constant-time compare,
length pre-check, revocation/suspension fail-closed); deny-by-default
`AuthenticatedRouter` over `com.sun.net.httpserver` with Virtual Threads,
RFC 3986 `{param}` path patterns (literal precedence, traversal/encoded-slash
rejection), 404/405/413 bounded-body handling, mandatory `AccessPolicy` per
route and a `registeredRoutes()` audit; `__Host-kw_session`/`kw_dev_session`
BFF `CookieCodec` (`forOrigin`, RFC 6265bis `clearCookieHeaderValue`, multi-line
`Cookie` extraction); and `CsrfGuard` (`same-origin`/`none` `Sec-Fetch-Site`
and `Origin` allow-list before body reads, JSON or constant-time per-session
CSRF token). Host SPIs (`IdentityProvisioner`, `SessionStore`,
`ScopedTokenLookup`) are pure seams implemented by the host app; the 16-test
`AuthSecurityGateTest` negative suite pins all security boundaries over real
loopback HTTP.

## Applications (`apps/`)

### wilds-headless — headless CLI & WAL replay rig

**Status:** in-development · **Artifact:** `com.kotharworks:wilds-headless` ·
**Package:** `com.kotharworks.wilds.headless` · **Depends on:** `wilds`

Zero-server command-line harness over the pure `WildsEngine` (Ticket 05). Subcommands:
`run` registers seeded test owners, queues sample `Travel`/`Wait` loops, and
fast-forwards 24 logical hours in milliseconds via `advanceToNextEvent()`;
`replay --wal <file>` re-drives a `.jsonl` write-ahead log (`WalRecord` `clockAdvance`/
`commandEntry` entries, optional `WAL_HEADER` version triple) and verifies pinned
`WorldHash` values inline bit-for-bit; `inspect` prints the seeded bootstrap place
graph with edge travel times and `glance`/`look`/`examine` stub descriptions. Seeds
accept signed decimal or full 64-bit unsigned `0x`/`0X` hex. Run via
`mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-headless -am
-Dexec.args="inspect --seed 0x49524F4E"`.

### wilds-server — Ironwood Wilds server host

**Status:** in-development · **Artifact:** `com.kotharworks:wilds-server` ·
**Package:** `com.kotharworks.wilds.server` · **Depends on:** `wilds`, `auth`

Hexagonal runtime core of the Ironwood Wilds online server (Tickets 07-09), on
Java 25 Virtual Threads with in-memory reference adapters (`adapters/memory/`) or
production PostgreSQL persistence (`--storage=postgres` via HikariCP + JDBI3).
`domain/WorldHost` is the single writer: a dedicated virtual thread drains a bounded
`ArrayBlockingQueue` mailbox over an injectable `ClockPort` enforcing **strict
Commit-Before-Mutate** WAL ordering (pure Phase-1 validation first,
`WorldStorePort.appendCommand` at `currentLogicalMs` before any state mutation,
`503 STORAGE_UNAVAILABLE` with untouched RAM on store failure; an append whose
outcome is unknown — commit landed, ack lost — is reconciled from the WAL tail before
any further command, append or checkpoint), seat-scoped `cmdId`
UUID idempotency with bounded LRU receipt caching and WAL-backed pre-checkpoint
reconstruction, pre-mutation `CLOCK_ADVANCE` anchors plus a quiet `ClockPump`
adapter, lock-free volatile `WorldView` MVCC reads plus a lock-free
`currentLogicalNowMs()` display clock extrapolated from the last committed anchor
(so `/play` progress bars and `/api/v1/meta` stay continuous across reloads), with
checkpoint-hash-verified + WAL-tail boot recovery, `PAUSE_WHILE_DOWN` clock resume
(logical time never advances offline), periodic + graceful-close checkpointing
(retention: latest ten) and a best-effort final `CLOCK_ADVANCE(currentLogicalMs)`
anchor so sub-quiet uptime survives rolling restarts and the exported WAL replays
to the exact shutdown hash.
`adapters/postgres/` ships the zero-framework `SqlMigrator` (plain versioned
`V###__*.sql` under `db/`: `V001__auth.sql`, `V002__wilds_meta.sql`,
`V003__wilds_world.sql` — gameplay-neutral `auth`/`wilds_meta` schemas and a
100% append-only `wilds_world` (`command_wal` with dense per-world `wal_seq`,
`world_checkpoints`, indexed `world_events`), `pg_advisory_lock` serialized with
pooled-session cleanup, run at boot on a dedicated connection without the runtime
pool's socket timeout, one transaction per script, refuses to boot on a newer
database or duplicate/out-of-order scripts), `PostgresAuthStore` (JIT provisioning
under `pg_advisory_xact_lock(hashtextextended(...))` — zero orphan users, zero
23505 races; SHA-256-only session/token credentials), `PostgresWorldStore`
(append-only WAL + batched `ON CONFLICT (world_id, event_cursor) DO NOTHING`
event backfill, latest-ten checkpoint retention, cursor-streamed `exportWalJsonl`
byte-compatible with `wilds-headless` replay; `payload_json` is written and read
exclusively through `libs/wilds`' `EventPayloadCodec`, so no adapter depends on another). The Testcontainers 2.x suite (`postgres/`) boots a singleton
`postgres:17-alpine` once per JVM and proves migration idempotency, 20-virtual-
thread JIT identity races, split-brain store-fault immunity (RAM never ahead of
WAL, HTTP 503) and full `kill -9` recovery with zero cursor rewind.
`domain/PrintableText` holds the printable-text and seat-name rules shared by the HTTP
adapter and both stores. `domain/SeatAuthorizer` bridges `libs/auth`
principals to permanent seats (`ports/SeatRecord`) and in-world `OwnerId`s, with
role quota charter policy (`ControllerKind`), `wilds:seat` bot-token resource
scoping, and per-seat token-bucket rate limiting (`429 RATE_LIMITED`). `ports/`
defines `AuthStorePort` (JIT user upsert, web sessions, scoped API tokens,
`suspendUser`) and `WorldStorePort` (seats, append-only WAL, idempotent events with
public/private projection, checkpoints + WAL export) implemented by
`adapters/memory/` and `adapters/postgres/`. HTTP:
`/dev/login`, `/auth/*` (dev-local persona switcher or OIDC Authorization Code +
PKCE), seat chartering `POST /api/v1/seats` (submits `RegisterOwner` through
`WorldHost` — zero UI-private mutation backdoors), `iw_bot_` token minting for
non-HUMAN seats, `GET /api/v1/meta|rules|observation|examine|events|public/events`,
synchronous `POST /api/v1/commands` (server-injected `OwnerId`, raw
`register_owner` rejected), `POST /api/v1/admin/users/{userId}/suspend`, and live
`GET /api/v1/stream?since=` Server-Sent Events over chunked virtual-thread
responses with replay, live fan-out, keepalives and clean shutdown — all registered
exclusively through `libs/auth`'s deny-by-default `AuthenticatedRouter`. CLI flags:
`--port`, `--seed`, `--time-scale`, `--pump-interval-ms`, `--bind` (127.0.0.1),
`--auth-mode dev-local|oidc`, `--storage memory|postgres`, `--jdbc-url`, `--db-user`,
`--db-password`, `--checkpoint-interval-ms` (300000), OIDC endpoints, and per-seat
rate limits. Run via
`mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-server -am`.
Ticket 09 adds `adapters/web/` — an escape-by-default HTML builder (`Html`,
`RawTrustedHtml` as the single explicit bypass, name- and HTML5-void-guarded
tags/attrs) rendering
the SSR structural console: `GET /play` (registered PUBLIC with HUMAN_ONLY
enforced in-handler so anonymous browsers get the mandated 303 -> `/auth/login`
instead of a bare 401; unseated users get a Claim Seat form, seats still awaiting
their `RegisterOwner` a self-refreshing page capped at ten attempts with a Retry
registration form, seated users the
console — header bar with multi-seat switcher (`?seatId=`, defaulting to the
first `HUMAN` seat) and form logout redirecting to `/chronicle`, "While You Were
Away" catch-up banner via `?since=`, fogged place & topology panel with
`[Examine]` drawer, actor & task queue panel with integer-formatted
energy/duration and `expectedRevision`-stamped controls routed strictly through
`POST /api/v1/commands`, and an SSE-fed live event log tail) plus the
public `GET /chronicle` observatory (world seed/version triple, logical clock,
`ControllerKind` seat breakdown, newest-tail `publicSummary`-only feed). Vanilla
`/assets/wilds-ui.js` (< 200 LOC) + `wilds.css` drive it: native `EventSource`
with immediate header flush, `Last-Event-ID` reconnect, and seat-scoped
`localStorage` cursor resume, `timeScale`-aware `requestAnimationFrame`
progress-bar interpolation from `(startedMs, dueMs, serverNowMs)` with zero
polling, coalesced observation refreshes, and non-destructive `409 STALE_STATE`
recovery. Supporting changes: `WorldStorePort.findAllSeats` (public roster),
`WorldStorePort.readRecentEvents` + `WorldHost.readRecentSeatEvents`/`readRecentPublicEvents`
(indexed newest-tail queries), and `WorldHost`/`WildsServer`
`closeWithoutCheckpoint()` — a deterministic in-process `kill -9` for the crash
rig that skips the final anchor/checkpoint — all locked together by
`FullSpineIntegrationTest` (Testcontainers PostgreSQL + loopback HTTP/SSE:
auth/CSRF/logout/XSS escaping E2E, multi-seat & two-user fog isolation with
monotonic SSE cursors, mid-flight crash with `PAUSE_WHILE_DOWN` recovery and
bit-for-bit `wilds-headless replay` `WorldHash` parity).

## System tests (`systest/`)

### wilds-systest — dockerized live system test suite

**Status:** in-development · **Artifact:** `com.kotharworks:wilds-systest` (opt-in
`-Psystest` reactor profile) · **Package:** `com.kotharworks.systest` · **Depends on:** —
(black-box: no server/engine dependency; JUnit 5 + Playwright Java, test scope)

`./systest.sh` (repo root) runs everything in Docker: `mvn install` with the regular
unit + Testcontainers integration suite, then the live suite against a real
`wilds-server --storage postgres` + PostgreSQL 17 stack, then a SIGKILL/restart phase.
Live tests mix **bot mode** (scripted `SCRIPT_BOT` agents over REST/SSE with scoped
`iw_bot_` tokens) and **human mode** (emulated players in headless Chromium driving the
SSR `/play` console and `wilds-ui.js`), plus mixed scenarios, security, regression and
crash-recovery suites. The runner joins the server's network namespace, so the
dev-local loopback fuse is untouched. Failure artifacts (screenshots, traces, page
HTML, server logs) land in `systest/reports/`. See [`systest/README.md`](systest/README.md).

## External dependencies

| Dependency    | Scope | Used by                          |
| ------------- | ----- | -------------------------------- |
| JUnit Jupiter | test  | `wilds`, `auth`, `wilds-headless`, `wilds-server` |
| nimbus-jose-jwt | compile | `auth` (transitively `wilds-server`) |
| PostgreSQL JDBC driver | compile | `wilds-server` |
| HikariCP | compile | `wilds-server` |
| JDBI3 core | compile | `wilds-server` |
| error_prone_annotations | provided | `wilds-server` (resolves @GuardedBy inside JDBI3/HikariCP class files under `-Werror`) |
| slf4j-simple | runtime | `wilds-server` (clean provider for HikariCP/Testcontainers logs) |
| Testcontainers junit-jupiter + postgresql | test | `wilds-server` (singleton `postgres:17-alpine`) |
| Playwright Java (+ bundled Chromium) | test | `wilds-systest` (human-mode browser tests; `-Psystest` only) |
| junit-platform-console-standalone | runner | `wilds-systest` (live-suite launcher inside Docker) |
| wilds-headless | test | `wilds-server` (cold `HeadlessMain` WAL replay parity) |

## Dependency graph

```text
libs/auth           (nimbus-jose-jwt only)
  └── apps/wilds-server
libs/wilds           (zero runtime deps)
  ├── apps/wilds-headless
  └── apps/wilds-server
systest              (no module deps — exercises the deployed apps over HTTP/SSE/browser)
```
