# Ticket 07 — Server Hexagonal Core: WorldHost, SeatAuthorizer, REST & SSE (In-Memory)

- **`st` Key:** `9ba362ce` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Done
- **Depends on:** Tickets `04` (`d8ed0e93`) and `06` (`bbc37528`)
- **Modules touched:** `apps/wilds-server`, `PROJECTS.md` *(mark `apps/wilds-server` `in-development`)*

---

## 1. Objective

Build the hexagonal runtime core of `apps/wilds-server` on Java 25 Virtual Threads using fast `InMemory*Store` adapters first:
1. **`SeatAuthorizer`:** Bridges `libs/auth` (`Principal`) -> permanent `seat_id` -> in-world `OwnerId`, enforcing role quotas, bot-token resource scoping, and per-seat token-bucket rate limiting.
2. **Single-Writer `WorldHost` Mailbox:** Enforces **strict Commit-Before-Mutate** WAL ordering, `cmdId` UUID idempotency, `CLOCK_ADVANCE` anchors, pure Phase-1 validation (`409 STALE_STATE` on `expectedRevision` mismatch), and lock-free `WorldView` MVCC reads.
3. **Unified REST + SSE Wire Contract:** `/dev/login` & `/auth/*`, Seat chartering (`POST /api/v1/seats`), `GET /api/v1/meta|rules|observation|examine|events|public/events`, synchronous `POST /api/v1/commands`, and live Virtual-Thread SSE (`GET /api/v1/stream?since=cursor`).

---

## 2. Explicit Non-Goals

- Do **not** add PostgreSQL/JDBI or SSR HTML `/play` views in this ticket (Tickets `08` and `09`).
- Zero UI-private mutation backdoors: **every** world mutation flows through `WorldHost` (`POST /api/v1/seats` submits `RegisterOwner` into `WorldHost`; all actor mutations go through `POST /api/v1/commands`).

---

## 3. Deliverables & Structure

### 3.1 Ports & In-Memory Adapters (`ports/` & `adapters/memory/`)
- **`AuthStorePort.java`**:
  - JIT upsert `(ExternalIdentity, displayName) -> UserRecord(UserId, suspendedAt, maxSeatsOverride)`
  - Web sessions (`createSession`, `findActiveSessionBySha256`, `revokeSession`)
  - Scoped API tokens (`createToken`, `findActiveTokenBySha256`, `revokeToken`)
  - `suspendUser(UserId, reason)` (immediately invalidates any in-memory auth cache!).
- **`WorldStorePort.java`**:
  - Permanent metadata: `createSeat(UserId owner, String seatName, ControllerKind kind)` *(validates trimmed printable non-control `seatName` of `3..40` chars without banning `<`/`>`/`&`/`"` — output escaping lives in HTML rendering!)*, `findSeatsByOwner(UserId)`, `findSeatById(long seatId)`, `ensureActiveWorld(...)`.
  - Append-only world WAL & cache *(strictly pre-mutation parameters so Commit-Before-Mutate needs zero post-apply `UPDATE`s!)*:
    - `boolean existsByCmdId(int worldId, UUID cmdId)`
    - `long appendClockAdvance(int worldId, long admittedMs, Long worldHash)`
    - `long appendCommand(int worldId, long admittedMs, long seatId, UUID cmdId, WorldCommand cmd, Long worldHash)`
    - `void appendEvents(int worldId, List<StoredWorldEvent> events)` (idempotent on `(worldId, cursor)`)
    - `List<StoredWorldEvent> readEventsSince(int worldId, Long seatIdOrNullForPublic, long sinceCursor, int limit)` *(when `seatId != null`, returns private events for that `seat_id`; when `seatId == null`, returns events with `publicSummary != null` projecting **only** public fields — never `privateSummary`!)*
    - `void saveCheckpoint(...)` & `Optional<StoredCheckpoint> loadLatestCheckpoint(int worldId)` + `List<WalRecord> loadWalAfter(int worldId, long afterWalSeq)`.
- **`InMemoryAuthStore` & `InMemoryWorldStore`:** Thread-safe reference implementations supporting optional fault injection in tests (to simulate store write failures!).

### 3.2 `SeatAuthorizer` & Single-Writer `WorldHost` (`domain/`)
- **`ControllerKind.java`:** `HUMAN`, `SCRIPT_BOT`, `GENETIC_AGENT` (`HUMAN` allowed for `PLAYER` up to 1 seat; non-`HUMAN` or multi-seat up to 25 requires `RESEARCHER` or `ADMIN`).
- **`SeatAuthorizer.java`:**
  - Resolves caller's target `SeatRecord`:
    - `HumanPrincipal`: verifies `seat.ownerUserId().equals(principal.userId())` (supports `?seatId=` when a researcher owns multiple seats, defaulting to their single seat).
    - `ScopedTokenPrincipal`: verifies `resourceNamespace.equals("wilds:seat")` and `scopedResourceId == seat.seatId()`.
  - Applies per-seat token-bucket rate limit (default `10 req/sec`, burst `20` -> `429 RATE_LIMITED`).
- **`WorldHost.java`:**
  - Single dedicated Virtual Thread draining a bounded `ArrayBlockingQueue` mailbox (no `CompletableFuture` chains; callers submit a command envelope with a tiny synchronous `ReplySlot` latch/queue and block their Virtual Thread cleanly).
  - **Strict Commit-Before-Mutate Sequence on Command Submission:**
    1. Check `cmdId` (`UUID`) in `WorldHost`'s in-memory `Map<UUID, CommandReceipt>` cache (and `worldStore.existsByCmdId(worldId, cmdId)` for pre-restart commands): if already committed, return `200 OK` receipt immediately without re-executing!
    2. Determine `admittedMs = logicalClock.currentLogicalMs()`. If `engine.peekNextDueMs() <= admittedMs`, commit `appendClockAdvance(worldId, admittedMs, null)` **first**, then run `engine.advanceTo(admittedMs)` and persist/publish emitted events.
    3. Run **pure** `engine.validatePhase1(command)` against live `WorldState`:
       - If invalid -> return `409 Conflict` JSON (`errorCode`, `message`, `currentRevision`); **zero WAL write, zero state mutation!**
    4. Commit `appendCommand(worldId, admittedMs, seatId, cmdId, command, null)` to `WorldStorePort` **before** mutating `WorldState`:
       - If `WorldStorePort` throws an exception -> return `503 STORAGE_UNAVAILABLE`; **`WorldState` in RAM remains 100% untouched!**
    5. Apply command to `WildsEngine`, cache the resulting `CommandReceipt` under `cmdId`, persist emitted `WorldEvent`s, publish fresh volatile `WorldView`, complete `ReplySlot` (`200 OK` + updated `revision` & receipt), and push events to `EventBusPort`.
  - **Live Pump (`ClockAdapter`):** Wakes every `pumpIntervalMs` wall time (default `1_000ms`, configurable to e.g. `50ms` in fast integration tests; advances logical clock by `wallDeltaMs * timeScale`). Only writes `CLOCK_ADVANCE` when `peekNextDueMs() <= targetMs` **or** `targetMs - lastPersistedClockMs >= 60_000L` (zero idle WAL spam!).

### 3.3 HTTP & SSE Endpoints (`adapters/http/` & `adapters/sse/`)
All routes registered exclusively through `AuthenticatedRouter` *(add `com.kotharworks:auth` dependency to `apps/wilds-server/pom.xml`)*:
- `PUBLIC`: `GET /api/v1/meta`, `GET /api/v1/public/events?since={cursor}`, `/auth/login`, `/auth/callback`, plus `/dev/login` (only when `authMode == DEV_LOCAL`).
- `HUMAN_ONLY`: `GET /api/v1/auth/me`, `POST /auth/logout`, `POST /api/v1/seats` (creates `SeatRecord` + submits `RegisterOwner` to `WorldHost` with deterministic/generated `cmdId`).
- `ROLE_RESEARCHER`: `POST /api/v1/seats/{seatId}/tokens`, `DELETE /api/v1/seats/{seatId}/tokens/{tokenId}`.
- `AUTHENTICATED_ANY`:
  - `GET /api/v1/rules` (serializes `RuleRegistry` + `WILDS_RULES_VERSION`)
  - `GET /api/v1/observation` (lock-free read off volatile `WorldView` via `ObservationProjector`)
  - `GET /api/v1/examine?type=place|actor&id=...&zoom=glance|look|examine`
  - `GET /api/v1/events?since={cursor}`
  - `GET /api/v1/stream?since={cursor}` (Virtual-Thread SSE stream: call `exchange.sendResponseHeaders(200, 0L)` — `0L` enables chunked streaming in JDK `HttpServer`, whereas `-1L` sends no body! — replay missed events `> since`, stream live events as `id: <cursor>\nevent: <kind>\ndata: <json>\n\n` flushing after each event, send `: keepalive\n\n` every 25s, and wake/close active subscriber queues cleanly on `Server.close()` so tests never hang!)
  - `POST /api/v1/commands` (`{"cmdId":"<uuid>","type":"enqueue|replace_queue|cancel_queue|set_fail_policy",...}` — server injects caller's authorized `OwnerId` and rejects raw `register_owner` payloads).
- `ROLE_ADMIN`: `POST /api/v1/admin/users/{userId}/suspend`.

---

## 4. Tests & Acceptance Criteria

- **Domain `AuthSecurityGateTest` (Checks #7–#12 on Live Server):**
  - `PLAYER` attempting to charter a 2nd seat, charter a `SCRIPT_BOT` seat, mint an `iw_bot_` token, or hit `/api/v1/admin/*` -> `403 FORBIDDEN`.
  - User A attempting to read `/api/v1/observation?seatId=<UserB>` or submit `POST /api/v1/commands` targeting User B's `ActorId` -> `403`/`409 FORBIDDEN` (zero WAL rows written!).
  - Lateral Bot-Token Escape: Researcher with Seat #1 (`HUMAN`) and Seat #2 (`SCRIPT_BOT`) uses Seat #2's `iw_bot_...` token against Seat #1 -> `403 FORBIDDEN`.
  - Bot token attempting `POST /api/v1/seats` or `/tokens` -> `403 FORBIDDEN`.
  - Admin calls `POST /api/v1/admin/users/{id}/suspend` -> next JWT or `iw_bot_` request from that user immediately fails with `403 ACCOUNT_SUSPENDED`.
- **`WorldHostProtocolIntegrationTest`:**
  - Duplicate `cmdId` submitted 10× concurrently on Virtual Threads executes **exactly once** and returns identical receipts.
  - Stale `expectedRevision` returns `409 STALE_STATE` with current `revision`.
  - Fault-injected `InMemoryWorldStore` throwing on `appendCommand` returns `503` while `WorldView` and `engine.worldHash()` remain **completely unchanged**; subsequent retry with the same `cmdId` after clearing fault succeeds cleanly!
  - Live SSE client connects to `/api/v1/stream?since=0`, submits `Wait(1_000, 1)` at `--time-scale=20`, receives `TaskStarted` and `TaskBatchCompleted` events in exact cursor order without polling.
