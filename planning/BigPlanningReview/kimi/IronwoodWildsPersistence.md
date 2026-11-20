# Ironwood Wilds — Accounts, Persistence, and Schemas

## 1. Persistence model

Use PostgreSQL, but do not turn every game rule into SQL. The recommended model
is a **snapshot + authoritative effect journal**, with relational
identity/control-plane tables and rebuildable read projections.

| Data                                        | Canonical home                                                                           |
| ------------------------------------------- | ---------------------------------------------------------------------------------------- |
| Accounts, credentials, sessions, API tokens | Relational `iw_identity` tables                                                          |
| World lifecycle and household membership    | Relational `iw_world` control-plane tables                                               |
| Dynamic simulation state                    | In-memory world while running; durable versioned snapshots plus subsequent effect events |
| Commands/idempotency and their results      | Relational `iw_world.commands`                                                           |
| Client/UI query models                      | Rebuildable `iw_read` projections when needed                                            |
| Item/recipe/tech/biome definitions          | Versioned content pack; world stores its version and canonical hash                      |
| Human chronicle                             | Rendered from domain events, not separately invented text                                |

There must never be two independently editable values for the same stock,
health, queue, Favor, or deadline. Read projections are disposable views, not
alternate authorities.

## 2. Why not only relational rows or only snapshots?

### Fully relational live state

Good for queries and transactional updates, but requires a large
object/relational mapping layer and makes the future agenda, deterministic
replay, and isolated headless worlds harder. It also risks game rules migrating
into SQL adapters.

### Snapshot only

Excellent for a small local prototype, but weak for multiple accounts, crash
granularity, idempotent network commands, audits, and incremental recovery.
Whole-world writes become increasingly expensive.

### Recommended hybrid

- Full snapshot includes all canonical dynamic state, agenda, logical time,
  counters, reservations, and versions.
- Subsequent events contain authoritative state/scheduler effects sufficient for
  recovery.
- Commands are separately retained as intent, admission order, and idempotency
  result.
- Recovery folds recorded effects onto a snapshot without executing commands or
  publishing side effects.
- A separate replay verifier re-executes admitted commands under pinned rules
  and compares events/state hashes.

This is more than a save file but avoids an always-on SQL object model. For the
tiny slice, snapshots can be frequent and projections minimal.

## 3. Schema overview

```text
iw_identity   accounts and authentication
iw_world      world control plane, snapshots, commands, event/effect journal
iw_read       optional/rebuildable query projections
iw_audit      security and administrative audit records
```

All dynamic rows and IDs are scoped by `world_id`. A season reset creates a new
world row/ID; it never clears mutable rows in place.

The following are logical schemas, not final DDL. Final migrations should add
explicit `CHECK`, `UNIQUE`, foreign-key, retention, and index definitions and be
tested against disposable PostgreSQL clusters.

## 4. `iw_identity`

### `accounts`

```text
account_id              UUID primary key
login_name_normalized   text unique not null
display_name            text not null
status                  text not null             -- active, disabled, deleted
created_at              timestamptz not null
updated_at              timestamptz not null
```

Keep authentication identity separate from a household. The account has no
direct skills, items, Favor, or world power.

### `password_credentials`

```text
account_id              UUID primary key references accounts
algorithm               text not null             -- e.g. argon2id
password_hash           text not null             -- encoded hash + salt + parameters
updated_at              timestamptz not null
```

Use a maintained, vetted Argon2id implementation with calibrated parameters. Do
not write password hashing from `SamRng`, SHA-256, or a home-grown construction.
External sign-in/OIDC can be another credential table later.

### `sessions`

```text
session_id              UUID primary key
account_id              UUID not null references accounts
token_digest            bytea unique not null
created_at              timestamptz not null
expires_at              timestamptz not null
last_seen_at            timestamptz
revoked_at              timestamptz
user_agent              text
```

Only a digest of the bearer value is stored. Login rotates the session.
Expiry/revocation is checked on every request; sessions are not world-specific,
while household authorisation is.

### `api_tokens`

```text
token_id                UUID primary key
account_id              UUID not null references accounts
name                    text not null
token_digest            bytea unique not null
scopes                  jsonb not null
created_at              timestamptz not null
expires_at              timestamptz
last_used_at            timestamptz
revoked_at              timestamptz
```

Scopes should resolve to explicit permissions such as `read` and `command` for a
particular world/household. Do not store a raw token or place one in a URL.
Support rotation and revocation from the account page.

## 5. `iw_world` control plane

### `worlds`

```text
world_id                UUID primary key
season_key              text unique not null
seed_u64                text not null              -- exact unsigned 64-bit decimal
status                  text not null              -- opening, active, closing, archived
ruleset_version         bigint not null
ruleset_hash            bytea not null
generator_version       bigint not null
record_format_version   bigint not null
observation_version     bigint not null
logical_time_ms         bigint not null
committed_revision      bigint not null
last_event_seq          bigint not null
earliest_retained_seq   bigint not null           -- 1 for an unpruned world
latest_snapshot_seq     bigint
created_at              timestamptz not null
closed_at               timestamptz
```

The logical time and revision fields are updated with committed mutations;
snapshots/journal records must refer to the same positions. Seed is text to
preserve u64 exactly across JSON and SQL clients.

### `households`

```text
household_id            UUID primary key
world_id                UUID not null references worlds
banner_name             text not null
created_at              timestamptz not null
archived_at             timestamptz
unique (world_id, household_id)                    -- redundant but useful for scoped FKs
```

Dynamic household state—Favor, unlocked technology, roster counters, holding
simulation data—lives in snapshots/events and optional projections under the
same stable household ID.

### `household_members`

```text
world_id                UUID not null
household_id            UUID not null
account_id              UUID not null references iw_identity.accounts
role                    text not null              -- owner initially
joined_at               timestamptz not null
left_at                 timestamptz
primary key (world_id, household_id, account_id)
```

Add a constraint/trigger appropriate to the initial policy of one active
household per account per world and one owner per household. Co-op membership
can come later without merging account and character concepts. Commands
establish household authority from active membership plus token scope.

## 6. Durable simulation records

### `snapshots`

```text
world_id                UUID not null references worlds
snapshot_seq            bigint not null            -- last included event sequence
world_revision          bigint not null
logical_time_ms         bigint not null
record_format_version   bigint not null
engine_revision         text not null
ruleset_hash            bytea not null
generator_version       bigint not null
payload                 text not null              -- canonical UTF-8 JSON
payload_sha256          bytea not null
created_at              timestamptz not null
primary key (world_id, snapshot_seq)
```

The payload includes:

- every dynamic entity and stable ID counter;
- action queues and running deadlines;
- future agenda and deterministic sequence/tie counters;
- stock, skills, technology, structures, shipments, expeditions, raids;
- persisted base artifacts for materialised regions plus resource/site/region
  deltas and materialisation state; unmaterialised regions retain their pinned
  generator identity;
- reservations and entity generations;
- logical time, world revision, last event sequence;
- RNG state if any stream is stateful rather than counter-derived.

A snapshot is canonical and self-contained for all materialized state at its
recorded position. Unmaterialized regions still depend on the world's retained
pinned generator, as detailed in the generation plan. Do not hash raw C struct
memory: padding, endianness, compiler layout, and pointer values are not a save
format. Use typed field serializers.

Canonical record/command JSON uses a deliberately restricted profile rather than
cJSON's generic double representation:

- object keys sorted recursively by their UTF-8 bytes; duplicate keys rejected
  during parsing;
- authoritative 64-bit IDs/counters/times/seeds encoded as decimal strings;
  small integers have explicit bounds and one canonical decimal spelling; no
  NaN, infinity, or authoritative floating-point numbers;
- fixed-point values, if needed, serialize as an integer/string plus a
  schema-defined scale;
- strings contain only Unicode scalar values encoded as valid UTF-8: emit `"` as
  `\"`, `\` as `\\`, and U+0008/U+0009/U+000A/U+000C/U+000D as
  `\b`/`\t`/`\n`/`\f`/`\r`; emit every other U+0000–U+001F control as `\u00xx`
  with lowercase hex, never escape `/`, and emit every non-ASCII scalar directly
  as UTF-8 rather than `\u` escapes/surrogate pairs. Identifiers/schema keys are
  ASCII, and display text is not silently Unicode-normalised;
- arrays retain schema-defined order, and absent versus explicit `null` has
  typed meaning;
- request hashes cover the canonical admitted command envelope (`v`, world,
  command ID, type, expected revision, payload), never credentials, transport
  headers, or database timestamps;
- volatile SQL `created_at` and engine diagnostic strings are metadata, not
  inputs to snapshot/request hashes unless explicitly versioned into the record.

Snapshot/event/command artifacts are stored as canonical UTF-8 text so key order
and numeric spelling have defined hash/replay semantics; JSONB is suitable for
mutable scopes and projections, not as the canonical byte representation. cJSON
is only a parsing/tree aid inside scratch arenas; the typed canonical writer and
duplicate-key/number checks own the format. Long-lived values are copied into
the world arena before scratch is destroyed.

### `events`

```text
world_id                UUID not null references worlds
seq                     bigint not null
world_revision          bigint not null
logical_time_ms         bigint not null
event_type              text not null
event_format_version    bigint not null
audience_kind           text not null              -- public, household, admin
audience_household_id   UUID
subject_id              text
payload                 text not null              -- canonical client/audit JSON
effects                 text not null              -- canonical authoritative recovery JSON
created_at              timestamptz not null
primary key (world_id, seq)
```

`effects` includes the exact state and agenda changes needed for recovery.
`payload` supports observations and chronicle rendering. Both are versioned;
neither is a string-only description. **`effects` is an internal recovery field
and is never sent to ordinary clients**; API serialization selects only the
audience-safe event fields and payload.

An optional per-event hash chain can be added with a later record-format version
if tamper evidence becomes a requirement; it does not replace backups or
authentication. The slice relies on canonical payloads, snapshot hashes,
sequence/revision checks, and trusted database access rather than making another
cryptographic format a prerequisite.

Indexes should support `(world_id, seq)`,
`(world_id, audience_household_id, seq)`, `(world_id, event_type, seq)`, and
chronological administrative inspection. Audience filtering must occur before
returning rows to a client.

### `commands`

```text
world_id                UUID not null references worlds
account_id              UUID not null references iw_identity.accounts
command_id              text not null
request_hash            bytea not null
request                 text not null              -- canonical UTF-8 JSON
admission_seq           bigint not null
admission_logical_time  bigint not null
status                  text not null              -- committed, rejected
response                text not null              -- canonical UTF-8 JSON
committed_revision      bigint
created_at              timestamptz not null
primary key (world_id, account_id, command_id)
unique (world_id, admission_seq)
```

A unique admission sequence establishes replay order and is enforced by
`UNIQUE(world_id, admission_seq)`. Store domain rejections too when admitted, so
retry behavior is consistent. Early malformed/unauthenticated requests may be
rejected before this table because they were never validly admitted.

The original response lets a timed-out client safely retry. `request_hash` is
SHA-256 over the canonical request profile above and prevents accidental reuse
with a different payload. The pre-admission idempotency lookup is only an
optimisation; the writer rechecks `(world_id, account_id, command_id)` and the
hash inside the world transaction so future transport workers cannot race the
check.

## 7. Atomic commit protocol

For each world-writer mutation, one PostgreSQL transaction should contain:

1. the admitted command row and result, if client-originated;
2. one or more ordered domain event/effect rows;
3. world logical time, revision, event sequence, and counters;
4. agenda/state effects represented by those event rows;
5. projection changes or a transactional outbox;
6. optional snapshot insertion and world snapshot pointer update.

Then:

- commit PostgreSQL;
- mark the in-memory mutation committed;
- publish events/notifications and acknowledge the caller.

If the transaction definitely fails, stop world admissions and reload from
durable state. Continuing after a partially applied in-memory mutation risks
duplicated items or divergent deadlines. A bounded lazy-advance batch commits
its partial effects, agenda, and logical time as one such unit before the next
batch; budget exhaustion is not an error, but commands remain deferred until the
target is reached.

If the result of `COMMIT` is **unknown** (for example, the connection drops
after sending it), close admissions, establish a fresh connection, reload the
latest durable position, and reconcile world sequence plus the command row
before any retry. If present, return the stored result; if absent, re-admission
is safe only after recovery. Never blindly repeat a timer-originated handler:
restore its agenda entry and last applied scheduled-event ID, then let normal
due processing decide. A core invariant failure also closes admissions and
forces this recovery path; repeated failure leaves the world administratively
paused.

Timer-originated transitions use the same transaction path but have no client
command row. Recovery applies event effects without resending responses,
shipments, notifications, or other external side effects.

## 8. `iw_read` projections

These are optional for the slice. Add them when HTML/API queries justify indexed
relational views.

Possible tables:

### `household_summaries`

```text
world_id, household_id, world_revision, living_characters,
home_favor, holding_id, next_due_time, summary_json
```

### `characters`

```text
world_id, household_id, character_id, world_revision,
name, status, site_id, health, running_action_id,
complete_at_ms, pending_count, details_json
primary key (world_id, character_id)
```

### `character_skills`

```text
world_id, character_id, skill_key, xp, level, world_revision
primary key (world_id, character_id, skill_key)
```

### `action_queues`

```text
world_id, character_id, queue_revision, running_action,
pending_actions, world_revision
primary key (world_id, character_id)
```

### `inventory_balances`, `structures`, `shipments`, `expeditions`

Similar rows keyed first by world and owning entity, always carrying
`world_revision`. Map/discovery projections must include household ID where
visibility differs.

Projection rules:

- update transactionally or from the event journal with a checkpoint;
- never accept client writes;
- include revision and expose it to clients;
- can be dropped and rebuilt from snapshots/events;
- a lagging projection may be stale but cannot masquerade as a committed command
  response.

For the first slice, direct core observations plus the durable event table are
sufficient. Do not create every projection table on day one merely because the
schema lists them.

## 9. `iw_audit`

Use separate append-only tables for security and administrative operations:

- `auth_events`: login failure/success, token creation/revocation, session
  revocation, rate-limit events;
- `admin_actions`: world open/pause/close, migration, snapshot restore, account
  disable;
- optional `world_operation_events`: recovery, snapshot failure, journal
  verification.

Record actor/account, timestamp, request correlation ID, safe metadata, and
outcome. Never store passwords, raw session/API tokens, or unnecessary sensitive
request payloads.

Domain gameplay events belong in `iw_world.events`; security/admin history
belongs here. They have different retention and access policies.

## 10. Startup, snapshots, and shutdown

### Startup

1. Run/check migrations explicitly; do not silently mutate a production schema
   on an ordinary binary start.
2. Load world control row and verify active/compatible versions.
3. Load latest verified snapshot.
4. Verify/apply subsequent event effects in sequence order.
5. Validate state, agenda, counters, and hashes.
6. Rebuild or validate projections.
7. Apply the declared downtime policy (initially frozen logical time while
   down).
8. Only then open admissions and timer wake-ups.

### During operation

- Snapshot after a configurable number of events/bytes or elapsed time and at
  quiet maintenance points.
- Keep the previous known-good snapshot until the new snapshot and world pointer
  commit.
- Prune old journal/snapshot data only after retention and archive policy
  permits; never destroy the only recovery tail.
- Monitor commit latency because synchronous libpq work on samhttp's engine
  thread can stall all routes.

### Shutdown

1. Close new admissions for the world.
2. Resolve/reject in-flight requests according to their admission status.
3. Commit a final snapshot at the exact journal position.
4. Freeze logical time and mark runtime stopped.
5. Close database/network resources.

An operational restart resumes this world; it does not generate a new map.

## 11. Versions and migrations

Keep these independent:

| Version            | Governs                                                                         |
| ------------------ | ------------------------------------------------------------------------------- |
| API/command schema | HTTP JSON shape and error contracts                                             |
| Observation schema | Structured views visible to clients                                             |
| Ruleset            | Costs, formulas, definitions, mechanics                                         |
| Generator          | Base world generation                                                           |
| Record format      | Snapshot/event/effect encoding                                                  |
| Bot policy         | Only a particular bot's decisions; not game rules                               |
| Engine revision    | Diagnostic identity of code; not permission to reinterpret old records silently |

A running world pins ruleset/generator hashes. Do not deploy a new ruleset and
silently replay old commands under different mechanics. Either:

- keep the old world on its compatible engine until archived;
- write and test an explicit record/event migration; or
- start a new world/season.

A content pack gets a canonical hash after validation. Changing costs,
prerequisites, handler meanings, or generation behavior requires the appropriate
version bump. Cosmetic display copy can have a separate presentation version if
it cannot affect state.

## 12. World closure/reset procedure

1. Announce and mark the old world `closing`.
2. Stop accepting new commands; old tokens no longer authorise new mutations.
3. Finish/reject in-flight admissions by explicit rule.
4. Snapshot, verify, and archive the old world and its chronicle.
5. Create a **new `world_id`**, seed, ruleset/generator pins, and world state.
6. Create new household membership/simulation records for participating
   accounts.
7. Reopen admissions for the new world only.

Every command, session authorisation, token scope, event, entity ID, and cached
observation is qualified by world ID. Delayed responses and stale clients from
the old world cannot mutate the new one.

Accounts and explicitly listed account-level cosmetics/archives survive. World
Favor, technology, characters, items, and buildings do not unless a season rule
explicitly copies them.

## 13. Security, deployment, and operations

- TLS terminates at a reverse proxy; samhttp remains on a private/loopback
  listener.
- Use parameterised libpq calls (`PQexecParams`), not interpolated SQL values.
- Hide `libpq-fe.h` behind adapter implementation files.
- Copy PostgreSQL result data into the correct arena before `PQclear`; never
  retain request-arena pointers in world state.
- Separate migration and runtime database roles; grant only required
  schema/table privileges.
- Use generic login errors and rate limits to reduce enumeration and abuse.
- Back up PostgreSQL and periodically test an actual restore plus record
  verification.
- Never run destructive migration/self-test code against a development or
  production database; use disposable clusters.

## 14. Slice versus target storage

### Include in the vertical slice

- accounts, password credentials, sessions/API tokens;
- worlds, households, memberships;
- snapshots, events/effects, commands;
- atomic commit and restart test;
- simple direct observations and event polling;
- explicit migration/version fields.

### Defer until needed

- most `iw_read` tables;
- asynchronous outbox workers;
- partition management for huge event histories;
- read replicas;
- multiple live world-owner processes;
- transparent cross-ruleset migration;
- offline progression.

This provides real account and recovery behavior without spending the entire
project building a database-backed ORM.
