# Ironwood Wilds — Web Interface and Client Protocol

## 1. One protocol, several presentations

The public API is the product boundary. The browser, CLI, reference bot, tests,
and future evolved controllers all use the same commands and observations.

```text
Human browser -> HTML forms/fetch --+
C CLI --------> JSON HTTP ---------+--> auth -> application -> world writer
Scripted bot --> JSON HTTP ---------+
Future GA -----> isolated in-process environment using equivalent commands
```

The GA exception is transport only: its observations and legal command
vocabulary must match the public protocol. It receives no privileged live-world
access.

## 2. Transport recommendation

### Version 1: REST/JSON plus polling

Use REST/JSON over `samhttp` for the slice:

- commands are `POST` requests;
- observations are `GET` requests;
- updates use a durable cursor-based event endpoint;
- JSON and HTML share application services;
- production TLS terminates at Caddy/nginx/another reverse proxy in front of the
  plain-HTTP C server.

This fits minutes-scale actions. A one- or two-second poll is adequate, and
tests can explicitly advance logical time instead of waiting.

### Not version 1: WebSockets, SSE, or raw sockets

`samhttp` currently closes every connection, has synchronous handlers, and has
no WebSocket/SSE streaming abstraction. Adding either push protocol would be
infrastructure work before proving the game. The event cursor and command IDs
are designed so a push adapter can be added later without changing simulation
semantics.

A future WebSocket should be an additional observation/notification transport,
not a second rules API. Commands can remain REST even after notifications become
push-based.

## 3. API conventions

Base path: `/api/v1`.

- JSON request and response bodies use UTF-8.
- Stable machine keys are lower snake case; errors are upper snake case.
- Raw 64-bit seeds, sequence values, revisions, and logical timestamps are
  **decimal strings** in JSON to avoid JavaScript's 53-bit integer limit. Entity
  IDs are opaque typed strings (`chr-1042`, `site-iron-ridge-7`, or a UUID for
  an account/world/household), not numbers coerced by the client.
- Small bounded counts, levels, and durations may be JSON integers if their
  maximum is specified below `2^53`; otherwise use strings consistently.
- Every observation includes `world_id`, `world_revision`, `logical_time_ms`,
  and the relevant observation/rules versions.
- List/event endpoints have explicit limits and cursors; no unbounded response
  dumps.
- Unknown JSON fields are rejected for commands (helps expose client bugs) and
  safely ignored only where the protocol explicitly promises forward
  compatibility.

### Standard error

```json
{
  "error": {
    "code": "QUEUE_FULL",
    "message": "Alda already has the maximum number of pending actions.",
    "details": {
      "character_id": "chr-1042",
      "queue_revision": "18"
    }
  }
}
```

Suggested status mapping:

| Status | Use                                                                                                                     |
| ------ | ----------------------------------------------------------------------------------------------------------------------- |
| 400    | Malformed JSON, invalid shape, unknown command/action field                                                             |
| 401    | Missing/expired session or token                                                                                        |
| 403    | Authenticated but lacking account/token scope                                                                           |
| 404    | Unknown route/entity, or private entity that must not be disclosed                                                      |
| 409    | Authoritative game-rule/current-state rejection, stale revision, queue full, or reused command ID with a different body |
| 410    | Event cursor is no longer available; client must resynchronise                                                          |
| 429    | Rate limit                                                                                                              |
| 500    | Unexpected server fault; no internal details leaked                                                                     |
| 503    | World catching up, or temporarily paused for recovery/maintenance                                                       |

Asynchronous action-start failures are events, not retroactive HTTP errors.

## 4. Route outline

### Identity and world discovery

| Method | Route                               | Purpose                                                                         |
| ------ | ----------------------------------- | ------------------------------------------------------------------------------- |
| POST   | `/api/v1/auth/register`             | Create account, subject to registration policy                                  |
| POST   | `/api/v1/auth/login`                | Create a browser session                                                        |
| POST   | `/api/v1/auth/logout`               | Revoke current session                                                          |
| POST   | `/api/v1/account/tokens`            | Create a scoped CLI/bot token; display its secret exactly once                  |
| DELETE | `/api/v1/account/tokens/{token_id}` | Revoke a token                                                                  |
| GET    | `/api/v1/worlds`                    | Joinable/current/archived worlds visible to the account                         |
| POST   | `/api/v1/worlds/{world}/household`  | Create the account's household, or return its existing household for that world |
| GET    | `/api/v1/worlds/{world}/rules`      | Versioned public definitions and formulas needed by clients                     |

### Household observations

| Method | Route                                             | Purpose                                                         |
| ------ | ------------------------------------------------- | --------------------------------------------------------------- |
| GET    | `/worlds/{world}/household`                       | Summary, Favor, knowledge, roster, holding, next deadlines      |
| GET    | `/worlds/{world}/characters`                      | Roster summaries                                                |
| GET    | `/worlds/{world}/characters/{character}`          | Character, skills, equipment, health, queue, legal action hints |
| GET    | `/worlds/{world}/inventory`                       | Household/carried stock and capacities                          |
| GET    | `/worlds/{world}/holding`                         | Buildings, construction, defence plan, raid notices             |
| GET    | `/worlds/{world}/technology`                      | Known/available/locked nodes with exact prerequisites           |
| GET    | `/worlds/{world}/map?region=…`                    | Only geography visible to this household                        |
| GET    | `/worlds/{world}/expeditions` and `/{expedition}` | Party plan, progress, rounds, results                           |
| GET    | `/worlds/{world}/shipments`                       | Shipment state/history                                          |

All paths above are relative to `/api/v1`.

### Commands and events

| Method | Route                                                    | Purpose                                                   |
| ------ | -------------------------------------------------------- | --------------------------------------------------------- |
| POST   | `/api/v1/worlds/{world}/commands`                        | Submit exactly one versioned command                      |
| GET    | `/api/v1/worlds/{world}/commands/{command_id}`           | Recover the stored result after an ambiguous timeout      |
| GET    | `/api/v1/worlds/{world}/events?after={cursor}&limit=100` | Audience-filtered committed events and a safe next cursor |

A batch endpoint can be added later, but each element needs independent
idempotency and result semantics. It is unnecessary for the slice.

## 5. Command example

Request:

```http
POST /api/v1/worlds/7c9e8f1e-6f2e-4f65-9b13-2b6a2e4a0b10/commands
Authorization: Bearer <scoped-bot-token>
Content-Type: application/json
```

```json
{
  "v": 1,
  "command_id": "cmd-01JYC7N4M5W4E6K0S8Z7G4W4FD",
  "type": "character.queue.append",
  "expected_revision": "18",
  "payload": {
    "character_id": "chr-1042",
    "actions": [
      {
        "type": "move",
        "destination_site_id": "site-iron-ridge-7"
      },
      {
        "type": "mine",
        "site_id": "site-iron-ridge-7",
        "resource": "iron_ore",
        "count": 1
      }
    ]
  }
}
```

Successful response:

```json
{
  "command_id": "cmd-01JYC7N4M5W4E6K0S8Z7G4W4FD",
  "status": "committed",
  "world_revision": "903",
  "logical_time_ms": "184923010",
  "result": {
    "character_id": "chr-1042",
    "character_revision": "19",
    "running": {
      "action_id": "act-8801",
      "type": "move",
      "complete_at_ms": "185043010"
    },
    "pending": [
      {
        "action_id": "act-8802",
        "type": "mine",
        "queue_position": 0
      }
    ]
  },
  "events": [
    {
      "seq": "14021",
      "type": "action_started",
      "logical_time_ms": "184923010"
    },
    {
      "seq": "14022",
      "type": "action_queued",
      "logical_time_ms": "184923010"
    }
  ]
}
```

The event objects in a real response need their full versioned payloads; the
abbreviated example only demonstrates the envelope.

### Retry behavior

- Same account/world, same `command_id`, same canonical request: return the
  original result.
- Same ID but different request: `409 COMMAND_ID_REUSED`.
- Unknown outcome after a network failure: retry or query the command-result
  route.
- A stale `expected_revision`: `409 STALE_REVISION`; the client rereads and
  decides again. The server must not guess the player's intent.

Browser forms generate a unique hidden `command_id` when rendered and carry the
expected revision. Post/Redirect/Get plus idempotency prevents refresh from
duplicating actions.

## 6. Event feed without silent gaps

Response shape:

```json
{
  "world_id": "7c9e8f1e-6f2e-4f65-9b13-2b6a2e4a0b10",
  "events": [],
  "next_cursor": "14022",
  "has_more": false,
  "latest_committed_seq": "14022",
  "world_revision": "903"
}
```

Rules:

- `after` is exclusive. `limit` (default/maximum 100) bounds **raw world-journal
  rows scanned**, not visible events returned. The server examines at most that
  many consecutive committed rows, then filters their audience; a page can
  therefore be empty even when `has_more=true`, and scanning work remains
  bounded.
- `next_cursor` is the last raw sequence scanned, not necessarily the last event
  visible to this household. `has_more` is true exactly when
  `next_cursor < latest_committed_seq`; the client continues immediately with
  `next_cursor` until caught up.
- Active-world journals are not pruned in the slice. If a later retention policy
  prunes them, world metadata records `earliest_retained_seq`. A cursor before
  the position that can connect to that first row returns `410 CURSOR_EXPIRED`
  with earliest/latest positions; a cursor ahead of the latest committed
  sequence is `400`.
- On `410`, the client fetches a complete household observation at revision `R`,
  discards derived state older than `R`, and resumes from that response's
  `latest_committed_seq`. The current state is again authoritative; any
  unavailable chronicle interval is explicitly marked as a history gap. Never
  silently skip intermediate events as a small ring-buffer feed can.
- Events are filtered by audience before serialization; internal recovery
  `effects` are never included. Another household's hidden seq numbers may be
  inferred from gaps, but no private payload, target, or text is exposed.
- The feed is a replayable record, not the sole in-memory ring used for
  rendering.

Clients should treat events as notifications and use observations for
reconstructed state. Every projection/observation has a revision so a client can
detect stale state.

## 7. Browser experience

### 7.1 Pages

```text
/worlds                              world/season selection
/worlds/{world}                      household dashboard and urgent events
/worlds/{world}/characters/{id}      one character per tab
/worlds/{world}/map                  ASCII/text region and site details
/worlds/{world}/holding              buildings, stock, defence, raids
/worlds/{world}/technology           tech tree and research assignments
/worlds/{world}/expeditions/{id}     planning and round chronicle
/worlds/{world}/shipments            preparation, history, Favor
/worlds/{world}/chronicle            basic paginated household event history
/account                             sessions, bot tokens, archives
```

The dashboard answers “what needs a decision?” rather than merely dumping every
table: completed/failed actions, idle characters, arriving shipments, available
recruitment, upcoming raid, and wounded party members.

A slice-era page could look like this; it is a content/layout sketch, not a
graphical requirement:

```text
IRONWOOD WILDS                         HOUSE FELL       Favor: 12
World: First Charter                   Revision: 903    Next event: 01:42

  NEEDS A DECISION
  * Bran is idle at the old coppice.                 [Open Bran]
  * Toolmaking I is available to research.           [View technology]
  * Raiders are expected in approximately 18 minutes. [Plan defence]

  HOUSEHOLD
  Alda    Mining iron ore at Iron Ridge       01:42  [Open] [Queue]
  Bran    Idle at Old Coppice                     —  [Open] [Queue]
  Cerrin  Researching Toolmaking I              04:17  [Open] [Queue]

  CAMP: wood 18/40 | stone 9/30 | ore 6/20 | crates 1
  NEXT SHIPMENT: crate + 10 wood + 5 ore -> estimated 10 Favor
  RECRUIT: 10 Favor -> new character at camp           [Recruit]

  CHRONICLE
  14:22  Bran completed Gather Wood: +6 wood, +18 Woodcutting XP.
  14:20  Alda arrived at Iron Ridge by the eastern ford.
```

### 7.2 Multiple character tabs

The household roster has a **“Open in tab”** link for each character. Each URL
is stable and bookmarkable. All tabs share the account session but maintain
independent event cursors and display state.

- A command in one tab can invalidate another tab's expected revision.
- The other tab receives an event and refreshes that character; if the user had
  an unsubmitted form, preserve the inputs and explain the conflict rather than
  silently overwriting them.
- Countdown displays use the server's deadline and estimated clock offset for
  presentation only. Refresh/resolution always trusts the server.
- No tab holds an authoritative copy of the character.

This provides the requested Sims-like cycling through people without
implementing a client-side simulation.

### 7.3 Rendering strategy

Use `samhtml` for full pages and small vanilla-JS modules for polling,
countdowns, queue forms, and optional map interactions. The server can return
either full pages or small JSON observations; do not build two unrelated state
APIs.

Start with:

- semantic HTML tables/lists;
- `<pre>` ASCII maps plus accessible site lists;
- native forms for every essential command;
- visible queued/running states and failure reasons;
- plain-text combat and raid reports;
- a small stylesheet, no front-end framework.

JavaScript improves freshness and convenience but essential play remains
possible with native form POSTs and manual refresh. This also makes protocol
tests easier.

### 7.4 Action composer

A character page should show only currently known action types, then render
fields from rule metadata:

1. action (`move`, `mine`, `craft`, `research`, etc.);
2. known target/site;
3. recipe/count/parameters;
4. current prerequisites and estimated duration;
5. append/replace/clear controls;
6. predicted queue and explicit warning that pending estimates are not
   guarantees.

Rules metadata can describe fields and options, but C handlers remain
authoritative. Do not attempt a fully generic form generator for complicated
expedition/defence plans; dedicated accessible forms are fine.

## 8. Authentication and browser/bot differences

The protocol is the same; credentials differ.

Session and bearer secrets are independently generated from a CSPRNG (for
example, a vetted libsodium call or the OS `getrandom` API) with at least 256
bits of entropy. They never come from `SamRng`, a world seed, a password hash,
or a predictable ID. Rotation issues a new secret and revokes the old one.
Disabling an account immediately revokes all of its sessions and API tokens and
rejects subsequent use; ordinary in-flight commands must still pass the writer's
current account/membership check.

### Browser

- `Secure`, `HttpOnly`, `SameSite=Lax` or stricter session cookie;
- TLS required outside loopback;
- CSRF token on cookie-authenticated mutations and suitable Origin checking;
- session ID rotated at login/privilege changes;
- logout and administrative revocation;
- no token in URLs or HTML event streams.

### Bots/CLI

- scoped bearer/API token, shown once and stored hashed server-side;
- scopes restrict world, household, and read/command capability;
- expiry, revocation, rotation, and last-used metadata;
- token in `Authorization`, never query parameters;
- no cookie CSRF requirement for bearer-only requests.

Do not reuse `samvillage`'s deterministic seed-derived world token. It is a
local single-user convenience, not multiplayer authentication.

## 9. Rate limits and backpressure

Define separate budgets for:

- login/registration attempts per IP and account;
- commands per token/account/world;
- observations and event polls;
- expensive map/history queries;
- maximum JSON body, action count, queue size, and string length.

Per-IP login/registration and connection limits belong to the TLS reverse proxy
for the slice because current samhttp handlers do not expose the peer address.
Per-account/token/world limits remain in the application. If a later samhttp
extension uses `X-Forwarded-For`/`Forwarded`, it must accept them only from a
statically trusted proxy on a private/loopback socket whose proxy configuration
overwrites—not appends to or preserves—client-supplied forwarding headers;
direct/untrusted requests ignore them.

Return `429` with `Retry-After`. A well-behaved bot polls the event cursor
rather than every observation endpoint at high frequency. The reference bot
should include bounded exponential backoff with jitter for network errors and
must not retry a non-idempotent intent with a new command ID.

Because `samhttp` handlers are synchronous on one engine thread, expensive
snapshots, libpq calls, generation bursts, and GA work must not casually run in
request handlers. The initial slice accepts small synchronous DB transactions;
later scale requires an async/runtime bridge or separate work process.

## 10. CLI and reference bot

### CLI

A small C client can expose:

```text
samwilds login
samwilds worlds
samwilds status --world …
samwilds character …
samwilds queue … --json-file plan.json
samwilds events --world … --after …
samwilds command --world … --json-file command.json
```

It links libcurl/cjson, not `samwilds`. Raw JSON input/output is valuable even
if friendlier subcommands come later.

### Scripted bot

The first bot should implement a deterministic policy:

1. read rules and household observation;
2. recover/reconcile outstanding command IDs;
3. consume new events;
4. maintain wood/ore/tool targets;
5. assign idle characters;
6. dispatch a safe shipment;
7. recruit when affordable;
8. run a known expedition only when its policy's requirements hold;
9. stop at a test goal and print decisions/metrics.

It uses only the API and its own local planning state. This proves both
playability and machine readability before genetic algorithms enter the picture.

## 11. Protocol tests

- every route over a real loopback socket;
- malformed/truncated/oversized JSON and unknown fields;
- u64 values above JavaScript's safe integer range remain exact strings;
- two accounts cannot read or command one another's characters;
- cookie and bearer credentials cannot be swapped into unintended scopes;
- duplicate command IDs return byte-equivalent logical results and do not
  duplicate effects;
- stale revisions preserve client intent and reject cleanly;
- event filtering, paging, expired cursors, and resynchronisation have no silent
  gaps;
- native HTML forms and JS commands reach the same application command service;
- two open character tabs converge after a command from either;
- the reference bot runs the whole vertical slice without engine linkage.
