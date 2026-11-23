# systest — the KotharWorks system test environment

One command, everything in Docker, same result on every machine:

```bash
./systest.sh            # the full gate: unit + integration suite, then the live suite
```

Only Docker (with Compose v2) is required on the host. No JDK, Maven, browser or
PostgreSQL install needed. First run downloads the toolbox image layers and Maven
dependencies (a few minutes); later runs reuse both.

| Command | What runs |
| --- | --- |
| `./systest.sh` | `mvn install` with every regular test (incl. Testcontainers Postgres ITs), then the live suite incl. the crash/restart phase |
| `./systest.sh unit` | only the regular Maven suite |
| `./systest.sh live` | only the live suite (build skips unit tests) |
| `./systest.sh live --tag bot` | live tests tagged `bot` (repeatable) |
| `./systest.sh live --test Human` | live test classes whose name contains `Human` |
| `./systest.sh live --keep` | leave the stack running afterwards; `./systest.sh down` to remove |
| `./systest.sh live --soak 120` | longer bot-fleet soak |
| `./systest.sh live -- --select-method com.kotharworks.systest.bot.BotProtocolTest#rateLimit` | raw JUnit console launcher args |
| `./systest.sh clean` | remove stack, Maven cache volume, toolbox image, reports |

Exit code is `0` only if every selected stage passed — safe to use as a gate in
scripts, hooks and agent loops.

## What it does

```text
./systest.sh
  ├─ build toolbox image      JDK 25 + Maven + Chromium (cached; rebuilt only when the
  │                           Playwright version in systest/pom.xml changes)
  ├─ build  (container)       copy checkout → mvn -Psystest install
  │                           unit + integration suite (Testcontainers via host socket)
  │                           stage server / headless / systest classpaths in a volume
  ├─ up    postgres + server  wilds-server --storage postgres, time-scale 10, seed 1
  ├─ runner phase "main"      every live test except @Tag("after-restart")
  ├─ kill -9 server, boot it again on the same database
  └─ runner phase "after-restart"   only @Tag("after-restart"), in a fresh JVM
```

The runner container shares the server container's network namespace, so tests
reach the server on real `127.0.0.1:8080`. The dev-local auth startup fuse (loopback
only) stays fully intact — the test stack does not weaken any security setting.

## Two kinds of players

- **Bot mode** (`support/Bot`, `support/Api`, `support/SseStream`): a scripted agent
  driving a `SCRIPT_BOT` seat through its scoped `iw_bot_` token — exactly the public
  bot contract (`/api/v1/observation`, `POST /api/v1/commands` with `cmdId` +
  `expectedRevision`, SSE `/api/v1/stream`).
- **Human mode** (`support/Humans`, `support/HumanPlayer`): an emulated person in
  headless Chromium using the real SSR pages and `wilds-ui.js`. They log in through the
  persona page, click buttons, type with per-key delays and pause to think. Each
  `spawn()` is a separate browser profile (separate cookies/localStorage) — a different
  machine as far as the server knows.

Mixed scenarios run both at once in the same world (`mixed/`).

## Suites

| Package | Tag(s) | Covers |
| --- | --- | --- |
| `smoke/` | `smoke` | stack alive, clock moving, login redirect chain, public pages |
| `headless/` | `headless` `smoke` | packaged `wilds-headless` CLI as a real process, cross-JVM determinism (pinned hash) |
| `human/` | `human` | first session (find game → login → claim seat → wait → travel → examine → sign out), STALE_STATE recovery, "While You Were Away", two devices |
| `bot/` | `bot` `soak` | travel lifecycle over SSE, REST/SSE parity, idempotency, stale revisions, cancel, fail policy, malformed-input fuzz (never 5xx), rate limiting, concurrent fleet soak |
| `mixed/` | `mixed` | humans + bots simultaneously: fog isolation both ways, two humans, public chronicle |
| `security/` | `security` | anonymous/bad-token refusal, quotas, token scoping, cross-owner commands, CSRF, revocation, logout |
| `regression/` | `regression` | one test per fixed bug, named after the commit/ticket |
| `restart/` | `restart` `before-restart` / `after-restart` | kill -9 recovery: tokens, sessions, in-flight tasks, cursors, PAUSE_WHILE_DOWN clock |

## When something fails

Everything lands in `systest/reports/` (gitignored):

- `unit/<module>/` — surefire XML + text for the Maven suite
- `live/main/`, `live/after-restart/` — JUnit XML for the live phases
- `live/artifacts/<TestClass>/<method>/` — **for failed human-mode tests**: full-page
  screenshot (`.png`), page HTML, browser console log, and a Playwright trace
  (`npx playwright show-trace <file>-trace.zip` for a step-by-step replay)
- `live/server.log`, `live/server-before-crash.log` — server + Postgres logs

Iterating on one failing test: `./systest.sh live --test BotProtocol --keep`, read
the reports, then re-run. Use `--keep` to poke the live server:
`docker compose -f systest/compose.yaml exec server curl -s 127.0.0.1:8080/api/v1/meta`.

## Adding tests

Tests are plain JUnit 5 classes under `systest/src/test/java/com/kotharworks/systest/`.
They are black-box: **no dependency on server or engine code** — only HTTP, SSE and
the browser. New classes are picked up automatically.

1. Put the class in the package that matches what it exercises and add the matching
   `@Tag`. A bug fix gets a test in `regression/` named after the bug, with the
   commit/ticket in the `@DisplayName`, e.g. `"196992f: a researcher cannot revoke
   another account's bot token"`.
2. **The world is shared and cumulative within a run.** Use a seat name unique to your
   class (`World.botSeat("my-feature-bot")`); chartering is idempotent by name, so the
   helpers converge no matter which test ran first. Leave actors idle when you finish.
   Alice (`Persona.ALICE`) is a PLAYER with exactly one HUMAN seat
   (`World.ALICE_SEAT`); Sam owns the bot seats (researcher cap 25); never suspend a
   persona.
3. **Never `Thread.sleep` to synchronize.** The world runs on a wall clock at 10×; use
   `Await.until(...)`, `SseStream.await(...)`, `bot.awaitIdle()`, or Playwright's
   auto-waiting `assertThat(locator)...` / `human.sees(...)`.
4. Human tests: `@ExtendWith(Humans.class)`, take a `Humans humans` parameter, and
   `humans.spawn("name")` per person. Any uncaught page JavaScript error or 5xx fails the
   test even if its assertions passed.
5. Crash coverage: record facts with `RunState.put` in a `@Tag("before-restart")`
   method; assert them in a `@Tag("after-restart")` method (see `CrashRestartTest`).
6. Run `./systest.sh live --test YourClass` until green, then the full `./systest.sh`.

Knobs (env vars, all optional): `SYSTEST_SOAK_SECONDS`, `SYSTEST_EVENTUALLY_SECONDS`
(default 20 — the "eventually" ceiling), `WILDS_TIME_SCALE`, `WILDS_SEED`,
`MVN_EXTRA_ARGS` (e.g. `-Dtest=WildsEngineTest -Dsurefire.failIfNoSpecifiedTests=false`
for the unit stage), `DOCKER_SOCK` (non-default Docker socket path).

Local IDE runs work too: start a server on 127.0.0.1:8080 yourself, then run a test
class from the IDE with `-Psystest`; human-mode tests need Playwright's Chromium
(`SYSTEST_HEADED=1` shows the browser, `SYSTEST_SLOWMO_MS=250` slows it down).
