# Ironwood Wilds — Accounts and Persistent Storage

**Status:** proposal v0.1 · 2026-11-20 · author: Fable **Scope:** who a player
is, how they prove it, and what gets written down — with the schemas. **Feeds
ticket:** `9e03b041` ("which are missing: auth? world persistence?").

______________________________________________________________________

## 1. Summary

Three kinds of data with three different needs. Treating them alike is the usual
mistake.

| Kind              | Examples                                               | Shape                            | Lives for | Stored as                                             |
| ----------------- | ------------------------------------------------------ | -------------------------------- | --------- | ----------------------------------------------------- |
| **Identity**      | accounts, tokens, invites                              | small, relational, transactional | ever      | SQL rows — schema `auth`                              |
| **The world**     | Families, Actors, stock, queues                        | one object in memory             | a season  | a journal of commands plus snapshots — schema `world` |
| **The chronicle** | ladders, daily stats, the event archive, halls of fame | derived, append-only             | ever      | SQL rows — schema `chronicle`                         |

The central choice: **the live world is not rows in a database.** It is memory,
made durable by an append-only journal and periodic snapshots. SQL keeps what
SQL is good at — identity, and asking questions afterwards.

Everything has two adapters, files and Postgres. Files come first: they keep
tests hermetic and let the slice run before a database exists.

## 2. Accounts

### 2.1 The model

```
  account ──< token                 an account is a person, or a bot's owner
     │
     └──< family binding >── world  one House per account per season
```

- An **account** has a handle, an optional email, and a declared **kind** —
  `human`, `bot` or `hybrid` — which decides the ladder it is ranked on.
- A **token** is the only credential. Programs use one; in the first version, so
  does the website.
- The **engine never sees an account.** It knows Family ids. The mapping from
  account to Family lives outside it, which keeps journals and exports
  pseudonymous.

### 2.2 Flows

| Flow                        | Request                                           | What happens                                                                                               |
| --------------------------- | ------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **Register**                | `POST /v1/register {handle, house, kind, invite}` | Account row; token minted and shown **once**; the server issues `family.found`, journaled like any command |
| **Sign in on the web** (v1) | paste the token                                   | Kept in the browser. Exactly how SpaceTraders does it                                                      |
| **A token for your bot**    | `POST /v1/tokens {label, scope}`                  | A second token on the same account, revocable alone                                                        |
| **Revoke**                  | `DELETE /v1/tokens/{id}`                          | Dead at once                                                                                               |
| **Later**                   | password or OAuth; email recovery                 | See below                                                                                                  |

**Why token-paste first.** The first public build handles no passwords at all.
The early audience writes bots and is at home with tokens. And a proper password
hash costs about a tenth of a second of CPU, which must never run on the engine
thread — moving it off needs samhttp's detached responses, which arrive with
push. When passwords do come: `crypt_r` with yescrypt from libxcrypt, already on
every box this runs on, executed on a samplex worker.

### 2.3 Tokens

```
  iw_api_7k2m_Q4ZT…(52 characters)…
  │  │   │    └── secret: 32 random bytes, base32
  │  │   └─────── token id: the lookup key
  │  └─────────── kind: api | session
  └────────────── fixed prefix, so secret scanners can spot a leak
```

- Only `sha256(secret)` is stored. A stolen database yields no usable tokens.
- Comparison is constant-time.
- Random bytes come from `getrandom(2)`, in the server — never in the engine.
- SHA-256 already exists in `samgrognard_hash.c`. Lift it into `samdata`; the
  journal's chain needs it too.

### 2.4 In the request path

| Step                                             | Cost           | Notes                                                                    |
| ------------------------------------------------ | -------------- | ------------------------------------------------------------------------ |
| Parse the token; find its id in an in-memory map | a hash lookup  | The map is loaded at start-up and updated on mint and revoke             |
| Compare hashes; check revoked and expired        | —              | No database on the hot path                                              |
| Resolve account → Family for this world          | —              | Cached with the token                                                    |
| Rate-limit                                       | a token bucket | Per token: commands 5/s, burst 20; reads 10/s, burst 40 *(placeholders)* |
| Note last use                                    | —              | Batched by the writer thread                                             |

Scopes: `read` (views and events), `play` (commands), `admin`. A token can only
ever command its own account's Family.

Registration is limited per client address. The address comes from the header
Caddy sets, and is trusted only because the server listens on loopback.

### 2.5 Names

Handles and House names are the one place player-typed text enters the system.
Allowed: letters, digits, space, hyphen, apostrophe; 3–24 characters; unique on
a folded key; a short reserved list. The web client escapes them everywhere
regardless.

### 2.6 Privacy

Email is optional and nothing else is personal. Deleting an account blanks its
row to a tombstone; its House stays in the archive under its House name.

## 3. The world: journal and snapshots

### 3.1 Why not rows

|                       | A. Rows are the state     | B. Memory, saved now and then                             | C. Memory, journal, snapshots           |
| --------------------- | ------------------------- | --------------------------------------------------------- | --------------------------------------- |
| Write path            | A transaction per command | Nothing until the save                                    | Append one record                       |
| The engine stays pure | No — the rules speak SQL  | Yes                                                       | Yes                                     |
| A crash loses         | Nothing                   | Everything since the last save (samvillage: five minutes) | The last unflushed batch — about 100 ms |
| Replay, fork, the lab | No                        | No                                                        | **Yes**                                 |
| SQL over live state   | Yes                       | No                                                        | Through the chronicle                   |
| Verdict               | Wrong tool                | Fine offline                                              | **Recommended**                         |

C is your own automation-game note — "store only instruction logs and
deterministic RNG seeds" — plus snapshots so a restart does not replay a whole
season.

### 3.2 The journal

| Kind   | Holds                                                                                     | Written                   |
| ------ | ----------------------------------------------------------------------------------------- | ------------------------- |
| `cmd`  | An accepted command: `seq`, world time `t`, Family, canonical JSON                        | When a command is applied |
| `sys`  | A command the server issued itself — `family.found`, admin actions, later standing orders | Likewise                  |
| `mark` | A time mark; every few minutes also a **world hash**                                      | By the housekeeping timer |

- **Only accepted commands are journaled.** A rejection changed nothing.
- **Canonical JSON**: sorted keys, no whitespace, integers only — the
  samgrognard rules — so the bytes that are hashed are the bytes that are
  stored.
- **A hash chain.** Each record carries `sha256(previous ‖ seq ‖ t ‖ payload)`.
  Truncation and tampering show, and an exported season can be verified by
  anyone.
- **Hash checkpoints** are what make replay trustworthy: a replay that reaches a
  different world hash stops at the first mark that disagrees.

### 3.3 Snapshots

A binary file of sections: a header (format, engine revision, rules digest,
journal position, world time, world hash) and then one section per module, each
with a tag, a version and a length.

- Written field by field, little-endian, fixed widths. The world hash is a hash
  of this stream, so it does not depend on struct layout or padding — an
  improvement on `samvillage_world_hash`, which does.
- The engine thread serialises into a buffer — milliseconds for megabytes — and
  hands the buffer to the writer thread.
- Taken every ten minutes and at clean shutdown. Keep the last six and one per
  world day.

### 3.4 Recovery

1. Load the newest snapshot.
2. Replay every journal record after its position: `advance_to(t)`, then
   `submit`.
3. Check each hash checkpoint on the way.
4. Set the world clock to the last record's time and open.

It takes seconds. A crash loses only commands acknowledged in the final batch
window that never reached disk — a known, small, documented amount.

### 3.5 Epochs: when the code or the rules change

An **epoch** is one pairing of engine build and rules digest.

- Starting a different build, or changed rules, opens a new epoch.
- Snapshot sections carry versions, so a newer build can upgrade an older
  snapshot.
- A journal segment replays only under the build that wrote it. Keep the tag.

During alpha, short seasons make this rare: breaking changes ride the season
boundary.

### 3.6 Seasons, and the export

A season is one row in `world.world` with its own seed. Closing one writes a
final snapshot, the hall of fame, and an **export bundle**:

```
  season-01.iww/
    world.json            seed, pack, epochs
    journal/*.jsonl
    snapshots/*.swb
```

`samworld-lab replay`, `verify` and `fork` read it. It holds Family ids and
House names, no accounts. This bundle is the raw material for anything you make
from a season — and for the lab's most interesting question: *take the live
world at day twelve and run it forward a different way.*

## 4. Schemas

House conventions throughout: one database; a role created once, by hand, by
`0000_role.sql`; numbered idempotent migrations embedded at build time and
applied by `samworld-server --migrate`; ASCII-only SQL.

### 4.1 `auth`

```sql
CREATE TABLE IF NOT EXISTS auth.account (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    handle      TEXT NOT NULL,
    handle_key  TEXT NOT NULL UNIQUE,          -- folded; what uniqueness is checked on
    email       TEXT UNIQUE,                   -- optional
    pass_hash   TEXT,                          -- NULL until passwords exist
    kind        TEXT NOT NULL DEFAULT 'human'
                  CHECK (kind IN ('human', 'bot', 'hybrid')),
    role        TEXT NOT NULL DEFAULT 'player'
                  CHECK (role IN ('player', 'admin')),
    status      TEXT NOT NULL DEFAULT 'active'
                  CHECK (status IN ('active', 'suspended', 'deleted')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auth.token (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id    BIGINT NOT NULL REFERENCES auth.account(id) ON DELETE CASCADE,
    kind          TEXT NOT NULL CHECK (kind IN ('api', 'session')),
    scope         TEXT NOT NULL DEFAULT 'play'
                    CHECK (scope IN ('read', 'play', 'admin')),
    secret_hash   BYTEA NOT NULL,              -- sha256(secret); never the secret
    label         TEXT NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ,
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS auth.invite (
    code        TEXT PRIMARY KEY,
    note        TEXT NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_by     BIGINT REFERENCES auth.account(id),
    used_at     TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS auth.audit_log (    -- admin and account actions
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    account_id  BIGINT,
    action      TEXT NOT NULL,
    detail      JSONB NOT NULL DEFAULT '{}'
);
```

### 4.2 `world`

```sql
CREATE TABLE IF NOT EXISTS world.world (
    id         INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug       TEXT NOT NULL UNIQUE,           -- 'season-01'
    seed       BIGINT NOT NULL,
    pack       TEXT NOT NULL,                  -- 'ironwood'
    state      TEXT NOT NULL
                 CHECK (state IN ('preparing', 'open', 'closed', 'archived')),
    opened_at  TIMESTAMPTZ,
    closed_at  TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS world.epoch (
    world_id      INT NOT NULL REFERENCES world.world(id),
    epoch         INT NOT NULL,
    engine_rev    TEXT NOT NULL,               -- git sha of the build
    rules_digest  TEXT NOT NULL,               -- sha256 of the built ruleset
    overrides     JSONB NOT NULL DEFAULT '{}', -- any --set values in force
    first_seq     BIGINT NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, epoch)
);

CREATE TABLE IF NOT EXISTS world.journal (
    world_id    INT NOT NULL REFERENCES world.world(id),
    seq         BIGINT NOT NULL,               -- gapless within a world
    t           BIGINT NOT NULL,               -- world time, ms
    kind        TEXT NOT NULL CHECK (kind IN ('cmd', 'sys', 'mark')),
    family_id   INT,                           -- NULL for sys and mark
    payload     TEXT NOT NULL,                 -- canonical JSON: the bytes that were hashed
    chain       BYTEA NOT NULL,                -- sha256(previous || seq || t || payload)
    written_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, seq)
);

CREATE TABLE IF NOT EXISTS world.snapshot (
    world_id    INT NOT NULL REFERENCES world.world(id),
    seq         BIGINT NOT NULL,               -- the journal position it captures
    t           BIGINT NOT NULL,
    world_hash  BYTEA NOT NULL,
    format      INT NOT NULL,
    bytes       BYTEA NOT NULL,
    taken_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, seq)
);

CREATE TABLE IF NOT EXISTS world.family_binding (
    world_id    INT NOT NULL REFERENCES world.world(id),
    family_id   INT NOT NULL,
    account_id  BIGINT NOT NULL REFERENCES auth.account(id),
    PRIMARY KEY (world_id, family_id),
    UNIQUE (world_id, account_id)              -- one House per account per season
);
```

`payload` is `TEXT`, not `JSONB`, because Postgres re-serialises `JSONB` and the
chain is over exact bytes. Cast it when querying.

### 4.3 `chronicle`

Derived data. All of it can be dropped and rebuilt by replaying journals.

```sql
CREATE TABLE IF NOT EXISTS chronicle.event (   -- optional archive of the feed
    world_id   INT NOT NULL,
    id         BIGINT NOT NULL,                -- the feed cursor
    t          BIGINT NOT NULL,
    type       TEXT NOT NULL,                  -- 'action.done'
    family_id  INT,                            -- NULL = public
    actor_id   INT,
    data       JSONB NOT NULL,
    PRIMARY KEY (world_id, id)
);

CREATE TABLE IF NOT EXISTS chronicle.family_day (  -- one row per House per world day
    world_id   INT NOT NULL,
    family_id  INT NOT NULL,
    day        INT NOT NULL,
    actors     INT NOT NULL,
    favor      BIGINT NOT NULL,
    shipped    BIGINT NOT NULL,
    skill_sum  INT NOT NULL,
    deaths     INT NOT NULL,
    commands   INT NOT NULL,
    PRIMARY KEY (world_id, family_id, day)
);

CREATE TABLE IF NOT EXISTS chronicle.honour (  -- the hall of fame; outlives every season
    world_id    INT NOT NULL,
    category    TEXT NOT NULL,                 -- 'favor', 'war_effort', 'deepest_lair'
    league      TEXT NOT NULL,                 -- 'human', 'bot', 'hybrid'
    rank        INT NOT NULL,
    account_id  BIGINT REFERENCES auth.account(id),
    house_name  TEXT NOT NULL,
    value       BIGINT NOT NULL,
    PRIMARY KEY (world_id, category, league, rank)
);
```

How it is fed: when a world day rolls over, the engine thread builds that day's
rows from the omniscient statistics view and hands them to the writer thread.
Events are archived in batches as they leave the in-memory rings.

**This is the statistics-course payoff.** Questions become queries:

```sql
-- Which control layer sends the commands, hour by hour?
SELECT payload::jsonb -> 'meta' ->> 'src' AS src,
       t / 3600000                        AS world_hour,
       count(*)
FROM world.journal
WHERE world_id = 1 AND kind = 'cmd'
GROUP BY 1, 2 ORDER BY 2, 1;

-- How long to a first recruit: people against programs?
SELECT a.kind,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY e.t) / 60000.0 AS median_minutes
FROM chronicle.event e
JOIN world.family_binding b
  ON b.world_id = e.world_id AND b.family_id = e.family_id
JOIN auth.account a ON a.id = b.account_id
WHERE e.world_id = 1
  AND e.type = 'family.recruited'
  AND (e.data ->> 'nth')::int = 1
GROUP BY a.kind;
```

## 5. The file adapter

```
  var/season-01/
    world.json                  seed, pack, epochs
    journal/000001.jsonl        one record per line
    snapshots/0000088213.swb
    accounts.json               development only
```

```json
{"seq":88213,"t":73211000,"k":"cmd","f":12,"p":{"actor":"a-104","args":{},"op":"queue.clear"},"h":"9f2c…"}
```

Writes are temp file, `fsync`, rename — as in samtasker and samvillage. The file
adapter serves the tests, the lab, local play, and the export bundle, which is
simply this directory.

## 6. Ports

```c
typedef struct SamJournalSink {                 /* in libs/samjournal */
  void *impl;
  bool (*append)(void *impl, const SamJournalRecord *rec);   /* must not block */
  bool (*flush)(void *impl);
  void (*close)(void *impl);
} SamJournalSink;

typedef struct SnapshotStore {                  /* in the server's ports/ */
  void *impl;
  bool (*put)(void *impl, const SnapshotMeta *meta, const void *bytes, size_t len);
  bool (*latest)(void *impl, Samrena *arena, SnapshotMeta *meta,
                 const void **bytes, size_t *len);
} SnapshotStore;

typedef struct AccountStore {
  void *impl;
  AccountError (*register_account)(void *impl, const RegisterRequest *req,
                                   AccountRecord *out);
  AccountError (*mint_token)(void *impl, int64_t account, const TokenRequest *req,
                             TokenRecord *out);
  AccountError (*revoke_token)(void *impl, int64_t token_id);
  bool (*load_tokens)(void *impl, void (*each)(const TokenRecord *, void *), void *user);
  AccountError (*bind_family)(void *impl, int32_t world, int32_t family, int64_t account);
} AccountStore;
```

Each has a `file_` and a `pg_` adapter. The pattern is the one in `samtasker`'s
repository and samtechtree's `st_reader.h`.

## 7. The writer thread

- It receives finished byte buffers over one bounded queue and never touches the
  world.
- **Group commit:** one transaction per hundred milliseconds or 256 records,
  whichever comes first.
- **Acknowledge, then commit**, in the first version. A strict mode — reply only
  once committed — needs detached responses.
- **If storage falls behind,** the server turns read-only until the queue
  drains: commands answer `503`, views keep working.
- `libpq` is used by exactly this one thread, so it needs no lock. samdashboard
  needs an app-wide mutex because its handlers share the connection; this design
  sidesteps that.

## 8. Operations

| Matter              | Approach                                                                                                                                                 |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Role and migrations | `0000_role.sql` once, by hand; then `samworld-server --migrate`                                                                                          |
| Secrets             | `~/.config/ptah-web/samworld-server.env` holds `SAMWORLD_DB` — the ptah-web convention; never in the repository                                          |
| Backups             | Nightly `pg_dump` of `auth` and `chronicle`. The world is covered by its snapshots and the season export. Restore once, deliberately, before trusting it |
| Retention           | Journal kept whole for the season. Snapshots pruned. The event archive is optional per season                                                            |
| Without a database  | The server still runs, on files. With Postgres configured but unreachable at start, it refuses to open the world rather than fork history                |

## 9. Slice scope

| In the slice                                                    | After                                                   |
| --------------------------------------------------------------- | ------------------------------------------------------- |
| File adapters for everything                                    | Postgres adapters, when going online                    |
| Token-only accounts; invite codes                               | Passwords or OAuth; email recovery                      |
| Journal, snapshots, the crash-recovery test                     | Epochs with section upgrades                            |
| The export bundle, because it *is* the file adapter's directory | The chronicle: event archive, daily stats, hall of fame |
