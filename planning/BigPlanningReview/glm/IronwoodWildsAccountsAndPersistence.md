# Ironwood Wilds — Accounts & Persistence (IronwoodWildsAccountsAndPersistence.md)

> Who owns what data, where truth lives, and how seasons die gracefully.
> Companion: [Architecture](IronwoodWildsServerArchitecture.md) §4, §8 ·
> [Commands](IronwoodWildsCommandsAndSimulation.md) §6.

## 1. The rule: two kinds of data, two homes

1. **Account & meta data** (accounts, sessions, API keys, seasons, leaderboards,
   audit): plain relational rows in PostgreSQL — the
   samtasker/samtechtree/samdashboard house pattern (libpq, numbered migrations,
   `--migrate`, role-separated schema).
2. **World data** (everything inside one running world): **snapshot +
   append-only journal**, with Postgres as the durable store and the query
   projections rebuilt from events. The DB never holds authoritative world
   *state* — the engine does, and its artifacts (snapshot blobs + journal rows)
   are what persist.

Why not normalized world tables (a `people` table, a `buildings` table…): the
engine's state is ECS component arrays whose shape changes every feature; a
relational mapping of world internals couples schema migrations to gameplay
code. Snapshots+journal decouple them, and the determinism contract
(Architecture §4) needs the journal anyway. Relational **projections** (§5) give
the SQL answers (leaderboards, stats) without making SQL the truth.

## 2. Accounts model

- One **account** (email, password hash Argon2id, created_at, status).
- One **household per account per season** (enforced unique) — your folk live in
  exactly one world at a time. (Decision: keeps spectate/chronicle identity
  clean; multi-household accounts are a later data change, not an architecture
  one.)
- **API keys** (per account, scoped: `read` / `command` / `admin`), revocable,
  last-used timestamps.
- **Sessions** for browsers (token, expiry, IP recorded).
- Lifetime stats per account across seasons (games played, Charters total, folk
  lost) — fed by projections (§5).

## 3. Schemas (v1 DDL sketch)

Two PG schemas, two owners, mirroring the samtechtree discipline (one-time
`0000_role.sql`, then `--migrate` as the app role):

```sql
-- schema: accounts (owned by samwilds app role)
CREATE TABLE accounts (
  id            BIGSERIAL PRIMARY KEY,
  email         TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,            -- argon2id encoded
  display_name  TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'active',  -- active|suspended
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE api_keys (
  id         BIGSERIAL PRIMARY KEY,
  account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  key_hash   TEXT NOT NULL,               -- sha256(token); token shown once
  scopes     TEXT[] NOT NULL,             -- '{read,command}'
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_used  TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ
);
CREATE TABLE sessions (
  token_hash TEXT PRIMARY KEY,
  account_id BIGINT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
  expires_at TIMESTAMPTZ NOT NULL,
  created_ip INET
);

-- schema: wilds (owned by samwilds app role)
CREATE TABLE seasons (
  id           BIGSERIAL PRIMARY KEY,
  name         TEXT NOT NULL,             -- 'Ironwood I — The Boggy Spring'
  seed         TEXT NOT NULL,             -- worldgen seed + params doc id
  params       JSONB NOT NULL,            -- tier-1 rules overrides (rules-as-data)
  rules_digest TEXT NOT NULL,             -- engine + worldgen versions
  state        TEXT NOT NULL,             -- provisioning|open|closing|archived
  starts_at    TIMESTAMPTZ NOT NULL,
  ends_at      TIMESTAMPTZ,               -- planned close (raid-schedule anchor)
  world_epoch  BIGINT NOT NULL            -- tick 0 wall-time
);
CREATE TABLE households (
  id          BIGSERIAL PRIMARY KEY,
  season_id   BIGINT NOT NULL REFERENCES seasons(id),
  account_id  BIGINT NOT NULL REFERENCES accounts(id),
  name        TEXT NOT NULL,
  landing     JSONB NOT NULL,             -- chosen start region/tile
  state       TEXT NOT NULL DEFAULT 'active',  -- active| departed|dead
  UNIQUE (season_id, account_id)          -- one household per account per season
);
CREATE TABLE journal (
  seq        BIGSERIAL PRIMARY KEY,       -- per-season gapless (app-enforced)
  season_id  BIGINT NOT NULL REFERENCES seasons(id),
  tick       BIGINT NOT NULL,
  batch      BIGINT NOT NULL,             -- command batch this came from
  kind       TEXT NOT NULL,               -- 'cmd'|'event'|'tick_hash'
  payload    JSONB NOT NULL,
  at         TIMESTAMPTZ NOT NULL DEFAULT now()
);   -- append-only: revoke UPDATE/DELETE from the app role (audit posture)
CREATE TABLE snapshots (
  id         BIGSERIAL PRIMARY KEY,
  season_id  BIGINT NOT NULL REFERENCES seasons(id),
  tick       BIGINT NOT NULL,
  kind       TEXT NOT NULL,               -- 'cadence'|'shutdown'|'final'
  world_hash TEXT NOT NULL,               -- determinism anchor
  blob       BYTEA NOT NULL               -- engine-serialized ECS state
);
CREATE INDEX ON snapshots (season_id, tick DESC);

-- projections (rebuilt-able from journal; query-serving only)
CREATE VIEW leaderboard AS SELECT ...;    -- households × charter_points
CREATE TABLE household_points (           -- materialized, event-fed
  season_id BIGINT, household_id BIGINT,
  charters BIGINT NOT NULL DEFAULT 0, shipments BIGINT NOT NULL DEFAULT 0,
  folk_lost BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (season_id, household_id)
);
CREATE TABLE account_lifetime ( ... );    -- cross-season stats
```

Notes: journal rows are
`(commands applied) + (events emitted) + (per-tick world_hash)` — the same three
artifacts the engine's determinism contract produces, stored once.
`snapshots.blob` is opaque to SQL (engine codec; versioned, refuse-on-mismatch
like samvillage saves). Chartered points live *only* in projection + events —
never a second mutable truth.

## 4. Write paths (who writes what, when)

| Writer                                           | What                                                        | When                                               |
| ------------------------------------------------ | ----------------------------------------------------------- | -------------------------------------------------- |
| tick host (sim thread, via async journal writer) | journal appends (batch per tick), cadence snapshots (5 min) | during tick, fire-and-forget with backpressure cap |
| commands/                                        | accounts, sessions, api_keys, households (signup/landing)   | request time, outside sim                          |
| projection updater (event subscriber)            | household_points, lifetime stats                            | on event, idempotent by (seq)                      |
| admin console                                    | seasons lifecycle rows, audit rows                          | gated pages                                        |

Journal writes go through a bounded queue to libpq; if PG hiccups, the sim keeps
ticking with the journal buffered in-process (memory cap + spill to local file)
— a database outage must not stop the world, and the world_hash chain reveals
any gap on recovery.

## 5. Season lifecycle (the resets, made graceful)

```
provisioning  season row + seed chosen; worldgen runs + stats gates (Procgen §4);
              landing slots scored; (a staging world can be eye-balled via the
              map tool before opening)
open          signups, households land, tick runs; snapshots + journal flow
closing       announced end-week: no new households; final raid week scheduled;
              ship calls complete
archived      final snapshot + journal digest; projections frozen into
              leaderboard archive; chronicle rendered; blob retained
              (storage: one season ≈ journal (text, tens of MB) + snapshots —
              cheap; keep everything, it's the content)
```

- New season = new `seasons` row with fresh seed/params. Accounts, keys,
  lifetime stats carry over untouched — "server restarts" are a *feature* with a
  first-class data story, not an ops apology.
- Rollback/forensics: any tick is `snapshot(t-k) + journal replay k` — disputed
  raid, suspected dupe bug, or "watch week 3 again" all use the same mechanism.

## 6. Backups & ops

- PG: standard pg_dump cadence + WAL archiving (accounts/journal are the crown
  jewels; snapshots are re-derivable from journal but kept).
- Config: samhttp INI (ports) + season params in DB; secrets via env (PGPASSWORD
  etc. per house pattern).
- Retention: everything, indefinitely, until storage says otherwise — journals
  double as content (chronicle/timelapse) and QA (regression replays for rules
  changes, samgrognard-style).

## 7. Decision log

| #   | Decision                                                                   | Note                                                                       |
| --- | -------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| S1  | Accounts/meta relational; world = snapshot+journal, SQL is store not truth | decouples schema churn from gameplay; determinism needs the journal anyway |
| S2  | One household per account per season                                       | clean identity; later relaxation is data-only                              |
| S3  | Projections (points/leaderboards) event-fed, rebuildable                   | one mutable truth (events), many query shapes                              |
| S4  | Journal append-only, gapless seq, world-hash chain rows                    | audit + forensics + content from one artifact                              |
| S5  | Sim survives PG outages (bounded buffer + spill)                           | the world stops for nobody                                                 |
| S6  | Seasons are rows; archiving is a lifecycle, not deletion                   | resets become a designed feature                                           |
| S7  | Argon2id + scoped API keys + PG sessions                                   | house-standard crypto, bot-friendly, no OAuth in v1                        |
