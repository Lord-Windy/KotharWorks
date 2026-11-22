# Ticket 09 — Minimal SSR Console (`/play`) & Full-Stack End-to-End Integration Suite

- **`st` Key:** `128ef171` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Backlog *(auto-promotes when `aee4bb00` and `08da943e` complete)*
- **Depends on:** Tickets `05` (`aee4bb00`) and `08` (`08da943e`)
- **Modules touched:** `apps/wilds-server`, `PROJECTS.md`

---

## 1. Objective

Deliver the runnable "DikuMUD stock console in a browser" (`GET /play` and `GET /chronicle`) and lock down the entire Phase 0 structural spine with **`FullSpineIntegrationTest`** — proving Auth, CSRF, SSR HTML, REST, SSE, `WorldHost`, PostgreSQL WAL/Checkpoints, crash recovery, and cold `apps/wilds-headless` replay all fit together with **bit-for-bit `WorldHash` parity**.

---

## 2. Explicit Non-Goals

- Zero React/Vite/npm frontend toolchain.
- Zero ornate gameplay tabs (no Hold 3-Ring matrix, Tech Tree UI, PvM Expedition planner, or Persona portraits yet) — build a crisp, clean, extensible **Server-Rendered Structural Console** showing the neutral `Owner` / `Actor` / `Place` / `TaskQueue` / `EventStream` model so you can log in, click around, queue movements/waits, inspect places, and watch live timers immediately.

---

## 3. Deliverables & Structure

### 3.1 Escape-by-Default HTML Builder & Minimal Console (`adapters/web/`)
- **`Html.java`:** Pure Java 25 HTML builder where **all dynamic text values are HTML-escaped (`&`, `<`, `>`, `"`, `'`) by default** unless explicitly wrapped in `RawTrustedHtml` — eliminating reflected/stored XSS by construction with zero template-engine reflection!
- **`GET /play` (`HUMAN_ONLY`, redirects unauthenticated browsers to `/auth/login` or `/dev/login`):**
  - **Unseated View:** If the logged-in user has no Seat in the active world yet, renders a clean *"Claim Seat"* form (`seatName`, CSRF hidden token / JSON fetch -> `POST /api/v1/seats`).
  - **Seated Structural Console:**
    1. **Header Bar:** Seat name, World title & `nowMs`, auth identity + Sign Out button (`POST /auth/logout` with `csrf_token`).
    2. **"While You Were Away" Catch-Up Banner:** Reads `?since=<lastSeenCursor>` (or client `localStorage` cursor) and summarizes missed events since last visit.
    3. **Current Place & Fogged Topology Panel:** Renders current `PlaceDto` (`look` description + **[Examine]** button fetching `/api/v1/examine?...&zoom=examine` into a drawer) and discovered connected `PlaceEdge` buttons (**[Travel -> Ridge Trail (5.2s)]**).
    4. **Actor & Task Queue Panel:** Displays `ActorId`, `revision`, integer `energyMilli` gauge (`e.g. 98.4%`), active task countdown bar (`data-started-ms`, `data-due-ms`, `data-server-now-ms`), pending queue list (`0..5` slots), **[Wait 5s]** / **[Cancel Pending]** / **[Cancel Active]** / **Fail Policy (`ADVANCE` | `HALT_ON_FAIL`)** controls — **every control embedding `expectedRevision` and routing strictly through `POST /api/v1/commands`!**
    5. **Live Event Log Panel:** Pre-populated with recent `Owner` events and updated live via SSE (`/api/v1/stream?since=cursor`).
- **`GET /chronicle` (`PUBLIC`):**
  - Minimal public world observatory showing active world seed/version metadata, seat count (`HUMAN` / `SCRIPT_BOT` / `GENETIC_AGENT` breakdown), and the public world event feed.
- **Static Assets (`/assets/wilds-ui.js` & `/assets/wilds.css`, `< 200 LOC` vanilla JS):**
  - Connects native `EventSource('/api/v1/stream?since=' + cursor)` to append live events and refresh actor/place state (updating `expectedRevision` attributes!) when discrete tasks start/finish/fail.
  - Smoothly interpolates the active task progress bar locally via `requestAnimationFrame` from `(startedAtMs, dueAtMs, serverNowMs)` with **zero polling**.
  - On `409 STALE_STATE`, displays a non-destructive notice and refreshes the panel's `revision`.

### 3.2 Full-Stack End-to-End Integration Suite (`FullSpineIntegrationTest`)
Add a comprehensive integration test in `apps/wilds-server` (`pom.xml` adds test-scope dependency on `com.kotharworks:wilds-headless` or invokes `HeadlessMain` directly over the exported `.jsonl` WAL file) running against **real Testcontainers PostgreSQL** + **real loopback HTTP/SSE sockets**:

1. **Auth, XSS Escaping & CSRF E2E:**
   - Walk `GET /play` (unauthenticated redirect -> `/dev/login`) -> sign in as `dev-alice` -> receive `HttpOnly` session cookie.
   - Verify cross-origin `POST /api/v1/seats` (`Origin: https://evil.example`) is blocked by `CsrfGuard` (`403`).
   - Charter seat `"House <script>alert(1)</script>"` (31 chars, valid under `3..40` printable `seat_name` rule) and assert it renders escaped as `House &lt;script&gt;alert(1)&lt;/script&gt;` (zero raw `<script>alert(1)</script>`!) in **both** `/play` and `/chronicle` HTML!
2. **Multi-User Gameplay-Neutral Loop + Live SSE + Fog Isolation:**
   - Configure fast deterministic pump ticks (`pumpIntervalMs = 50`, `timeScale = 20` -> `1_000ms` logical advance per `50ms` wall tick) and open persistent SSE stream `/api/v1/stream?since=0` for Alice on a background Virtual Thread.
   - Submit `POST /api/v1/commands` (`enqueue`: `[Travel(plc:1:1), Travel(plc:2:1), Wait(1_000, 2)]`).
   - Assert SSE stream receives `TaskStarted` -> `TaskBatchCompleted` (`plc:1:1`) -> `PlaceDiscovered` (`plc:2:1`) -> `TaskBatchCompleted` (`plc:2:1`) -> `Wait` completions in strictly monotonic cursor order!
   - Sign in second user `dev-sam`, charter second seat at `plc:0:1`, fetch `/api/v1/observation` and `/play` -> assert `"plc:2:1"` appears **nowhere** in Sam's observation JSON or `/play` HTML (still fogged for Sam!) and Sam **cannot** see or command Alice's actor (`409 FORBIDDEN`).
   - Verify `"While You Were Away"` catch-up (`GET /api/v1/events?since=1` and `/play?since=1`) returns Alice's completed travel/wait history.
3. **Mid-Flight Crash, `PAUSE_WHILE_DOWN` Restart & Headless `WorldHash` Parity:**
   - Enqueue `Wait(5_000, 2)` (`5_000ms × 2` batches) for Alice, await `TaskBatchCompleted` for batch #1 on SSE via passive `CLOCK_ADVANCE`, then **abruptly abort** Server #1 (`closeWithoutCheckpoint()`, before batch #2 at `+10_000ms` is due).
   - Sleep `300ms` wall time (which at `20×` time-scale would advance `+6_000ms` past batch #2 if not paused!), then boot a **brand-new** `wilds-server` instance against the same PostgreSQL container.
   - Assert `PAUSE_WHILE_DOWN` held: immediately on recovery, logical `nowMs` equals the exact last committed `admitted_ms`, SSE cursors did **not** rewind, and batch #2 subsequently completes cleanly once the live pump resumes!
   - Quiesce/close Server #2 cleanly so no background pump tick races the export, export `wilds_world.command_wal` from PostgreSQL to a temporary `spine-e2e.wal.jsonl` file, and run `HeadlessMain.execute(new String[]{"replay", "--seed", ..., "--wal", ..., "--hash"}, out, err)`.
   - **Final Gate:** Assert the cold `apps/wilds-headless` `WorldHash` matches Server #2's final `WorldHash` **bit-for-bit**!

---

## 4. Acceptance Criteria

- Manual smoke check (`mvnd -q -Dmvnd.rawStreams=true compile exec:java -pl apps/wilds-server -am -Dexec.args="--storage=memory --auth-mode=dev-local --time-scale=5"`): opening `http://127.0.0.1:8080/play` lets you log in, claim a seat, travel between the 3 bootstrap places, queue waits, inspect places, and watch live SSE progress in real time.
- `mvnd test` runs every unit test, `WildsArchitectureGateTest`, all 12 `AuthSecurityGateTest` checks, Postgres persistence tests, and `FullSpineIntegrationTest` cleanly with **zero warnings (`-Werror`)**.
