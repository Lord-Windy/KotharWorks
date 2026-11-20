# 05 — Accounts, Auth & PostgreSQL Schema Architecture

You asked:
> *"How we deal with accounts, and persistent storage in general under schemas."*

Ptah already has a battle-tested PostgreSQL philosophy (`apps/samdashboard` owning `dashboard.*`, `apps/samtechtree` owning `techtree.*`, `libs/samtasker` using `libpq` single-transaction saves and idempotent DDL). For **Ironwood Wilds**, separating data into **three dedicated PostgreSQL schemas by lifecycle** solves accounts, bot keys, world wipes, and hypothesis archiving cleanly from day one.

---

## 1. Why Three Schemas? (Lifecycle Isolation)

In an online simulation with periodic **Server Restarts / Charter Seasons** (and population experiments), mixing accounts, historical experiment telemetry, and ephemeral tree stumps in one schema makes resets terrifying. Splitting by **retention lifetime** makes resets trivial:

```
PostgreSQL Database (ptah / ironwood)
│
├── SCHEMA auth        (PERMANENT — never wiped on season restart)
│   ├── accounts       Human & bot-owner identities + password hashes
│   ├── sessions       Browser HttpOnly cookie sessions (SHA-256 token hash)
│   └── api_keys       Bot / CLI Bearer tokens ("iww_live_...", SHA-256 hash)
│
├── SCHEMA wilds_meta  (PERMANENT CHRONICLE — grows across seasons & experiments)
│   ├── seasons        Seed, WorldHypothesisConfig JSONB, start/end timestamps
│   ├── chronicle      Final Household standings, Tribute totals, Hold tiers
│   └── metrics_hourly Population, wealth, skill & mortality time-series for charts
│
└── SCHEMA wilds       (EPHEMERAL WORLD — active Charter Season state)
    ├── world_clock    Monotonic sim clock_ms, active dispatch state, next IDs
    ├── sites_delta    ONLY surveyed or modified/depleted procedural sites!
    ├── households     Active charter for each account in the current season
    ├── holds          Hold location, raid threat, JSONB/structured defense doctrine
    ├── hold_buildings Built structures, condition anchors, active construction
    ├── hold_stocks    Yard vs. Vaulted item stacks
    ├── characters     Vitals, location, stamina anchor, equipment, mortality state
    ├── char_skills    (character_id, skill_id) → xp
    ├── char_items     Character pack & equipped gear
    ├── action_queues  Running action (started_ms, due_ms) + pending slots [0..3]
    ├── parties        Active PvM expedition groups & retreat doctrine
    └── events         Append-only season event log (seq, household_id, payload)
```

**Season Reset Workflow:**
1. Summarize final `wilds.households` into `wilds_meta.chronicle` and mark `wilds_meta.seasons.ended_at = now()`.
2. `DROP SCHEMA wilds CASCADE;` → re-apply `wilds` migration DDL (`--migrate`) → seed `wilds.world_clock` with the new `world_seed` and `WorldHypothesisConfig`.
3. Every human browser session and every bot `Bearer` token in `auth.*` stays valid — on their next request, they click **"Sign New Charter"** (or `POST /api/v1/charter/join`) and land fresh on the new coast!

---

## 2. Accounts, Browser Sessions & Bot API Keys (`auth` Schema)

Humans and bots authenticate to the **same Account**, which owns at most **one Household per active World/Season** (preventing multi-account mule feeding unless explicitly allowed in a sandbox bot-cohort test).

### 2.1 Dual Credential Paths, One Tenancy Context
- **Humans (Browser Tabs):** Register/login via standard HTML form (`POST /auth/register`, `POST /auth/login`).
  - Passwords hashed with **Argon2id** (or PBKDF2-HMAC-SHA256 if keeping zero external crypto libs via a small audited C11 SHA-256/HMAC module, matching `samgrognard`'s internal SHA-256 precedent!).
  - Login sets `Set-Cookie: iww_sid=<32-byte hex>; HttpOnly; SameSite=Lax; Path=/` (plus `Secure` behind TLS/Caddy/Nginx).
  - Mutating HTML form posts carry a per-session CSRF token; JSON API calls accept either the session cookie (with `SameSite` + custom header `X-IWW-Client: web`) or an `Authorization: Bearer` header.
- **Bots & CLI (`samwilds-cli`, Python/C bots):** Generate up to `N` named API keys from the Account tab (`iww_k_...`).
  - **Raw tokens are shown once and never stored in Postgres** — only ` token_sha256 BYTEA` is stored, so a database backup never leaks live credentials.
  - Bots pass `Authorization: Bearer iww_k_<hex>` on HTTP requests or in the opening `{"op":"auth","token":"..."}` line on the raw TCP bot socket.

### 2.2 Concrete `auth` DDL

```sql
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.accounts (
    account_id      BIGSERIAL PRIMARY KEY,
    username        TEXT        NOT NULL UNIQUE
                    CHECK (username ~ '^[a-zA-Z0-9_-]{3,32}$'),
    password_hash   TEXT        NOT NULL,   -- Argon2id / PBKDF2 PHC string
    is_bot_account  BOOLEAN     NOT NULL DEFAULT FALSE,
    is_admin        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auth.sessions (
    token_sha256    BYTEA       PRIMARY KEY CHECK (octet_length(token_sha256) = 32),
    account_id      BIGINT      NOT NULL REFERENCES auth.accounts(account_id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    user_agent      TEXT
);

CREATE TABLE IF NOT EXISTS auth.api_keys (
    key_id          BIGSERIAL PRIMARY KEY,
    account_id      BIGINT      NOT NULL REFERENCES auth.accounts(account_id) ON DELETE CASCADE,
    label           TEXT        NOT NULL CHECK (char_length(label) BETWEEN 1 AND 64),
    key_prefix      TEXT        NOT NULL,   -- e.g. "iww_k_7f3a" for UI display
    token_sha256    BYTEA       NOT NULL UNIQUE CHECK (octet_length(token_sha256) = 32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);
```

---

## 3. Live World Schema (`wilds.*`) & Sparse Procedural Deltas

### 3.1 Sparse Procedural Persistence (`wilds.sites_delta`)
Because `libs/samprocgen` deterministically computes every region `(rx, ry)` and its default sites from `world_seed`, **we never populate 100,000 wilderness sites into Postgres at world creation**.
- Untouched forest = **0 database rows**.
- A row enters `wilds.sites_delta` *only* when a site's state diverges from pure procedural math (e.g., ore depleted from `50 → 37`, regrow timer set, or a Barrow room cleared) or when a Household records discovering a hidden survey site (`wilds.household_discoveries`).

### 3.2 Core `wilds` Tables (Representative DDL)

```sql
CREATE SCHEMA IF NOT EXISTS wilds;

CREATE TABLE IF NOT EXISTS wilds.world_clock (
    singleton_id        INT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
    season_id           BIGINT  NOT NULL,
    world_seed          BIGINT  NOT NULL,
    rules_version       INT     NOT NULL,
    sim_clock_ms        BIGINT  NOT NULL,   -- authoritative simulation time!
    active_dispatch_id  INT     NOT NULL DEFAULT 0,
    dispatch_due_ms     BIGINT  NOT NULL DEFAULT 0,
    last_event_seq      BIGINT  NOT NULL DEFAULT 0,
    saved_wall_ts       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS wilds.households (
    household_id        BIGINT PRIMARY KEY, -- packed SamWildsId (gen<<32 | idx)
    account_id          BIGINT NOT NULL UNIQUE, -- logical FK to auth.accounts
    house_name          TEXT   NOT NULL CHECK (char_length(house_name) BETWEEN 2 AND 40),
    charter_favor       BIGINT NOT NULL DEFAULT 0,
    total_tribute_sent  BIGINT NOT NULL DEFAULT 0,
    unlocked_tech_bits  BIGINT NOT NULL DEFAULT 1, -- bitmask (or child table >64 techs)
    created_sim_ms      BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS wilds.holds (
    hold_id             BIGINT PRIMARY KEY,
    household_id        BIGINT NOT NULL UNIQUE REFERENCES wilds.households(household_id),
    region_x            INT    NOT NULL,
    region_y            INT    NOT NULL,
    raid_heat           INT    NOT NULL DEFAULT 0,
    next_raid_due_ms    BIGINT NOT NULL,
    defense_doctrine    JSONB  NOT NULL     -- structured roles, vault priorities, fallback %
);

CREATE TABLE IF NOT EXISTS wilds.characters (
    character_id        BIGINT PRIMARY KEY,
    household_id        BIGINT NOT NULL REFERENCES wilds.households(household_id),
    given_name          TEXT   NOT NULL,
    status              SMALLINT NOT NULL,  -- 0=IDLE, 1=WORKING, 2=PARTY_PVM, 3=DEAD
    location_site_id    BIGINT NOT NULL,
    hp                  SMALLINT NOT NULL,
    max_hp              SMALLINT NOT NULL,
    stamina_anchor      SMALLINT NOT NULL,
    stamina_anchor_ms   BIGINT NOT NULL,
    wound_until_ms      BIGINT NOT NULL DEFAULT 0,
    died_sim_ms         BIGINT,
    epitaph             TEXT                -- e.g. "Fell to a Barrow-Wight in the Deep Ironwood"
);

CREATE TABLE IF NOT EXISTS wilds.char_skills (
    character_id        BIGINT   NOT NULL REFERENCES wilds.characters(character_id),
    skill_id            SMALLINT NOT NULL,
    xp                  BIGINT   NOT NULL DEFAULT 0,
    PRIMARY KEY (character_id, skill_id)
);

CREATE TABLE IF NOT EXISTS wilds.action_queues (
    character_id        BIGINT   NOT NULL REFERENCES wilds.characters(character_id),
    slot_idx            SMALLINT NOT NULL CHECK (slot_idx BETWEEN 0 AND 4), -- 0 = active
    action_type         SMALLINT NOT NULL,
    target_id           BIGINT   NOT NULL DEFAULT 0,
    param_id            INT      NOT NULL DEFAULT 0,
    remaining_reps      SMALLINT NOT NULL DEFAULT 1,
    started_sim_ms      BIGINT,             -- populated when slot_idx = 0
    due_sim_ms          BIGINT,             -- populated when slot_idx = 0
    PRIMARY KEY (character_id, slot_idx)
);

CREATE TABLE IF NOT EXISTS wilds.sites_delta (
    site_key            BIGINT PRIMARY KEY, -- deterministic hash/coords of (rx, ry, slot)
    remaining_yield     INT    NOT NULL,
    regrows_sim_ms      BIGINT NOT NULL DEFAULT 0,
    cleared_flags       INT    NOT NULL DEFAULT 0
);
```

---

## 4. RAM vs. PostgreSQL Sync & Reboot Time Semantics

### 4.1 RAM Is Authoritative; Postgres Checkpoints Dirty Rows
Running SQL queries inside every mining completion or bot poll would bottleneck throughput and couple `libs/samwilds` to `libpq`. Instead:
1. **Boot (`load_world`):** `pg_wilds.c` reads `wilds.*` once into `SamWildsWorld`'s arena pools and rebuilds the `SamWildsEventHeap` from every `slot_idx = 0` row in `wilds.action_queues` + `holds.next_raid_due_ms`.
2. **Runtime:** Every pool slot has a `bool dirty` bit. When a character finishes an action, levels up, or edits a queue, their slot bit flips `dirty = true`.
3. **Flush Cycle (Every 5s + `SIGTERM`):** The persistence adapter copies dirty structs into a staging buffer, clears the dirty bits, releases the world lock, and executes **one batched `BEGIN ... UPSERT ... COMMIT` transaction** via `libpq` (`PQexecParams` / `COPY` / `UNNEST` arrays).
4. **Immediate Writes:** `auth.*` mutations (account signup, API key creation/revocation) commit synchronously before returning HTTP `201`.

### 4.2 Simulation Clock vs. Wall Clock Across Downtime
Notice all timestamps in `wilds.*` are **`sim_clock_ms` (`uint64_t` milliseconds since Season Start)**, NOT `TIMESTAMPTZ` wall-clock dates! Why?
- If you take the server down for 30 minutes on a Friday afternoon to deploy an update, **`sim_clock_ms` pauses cleanly at the exact shutdown millisecond** and resumes when the server boots back up.
- Nobody's delve party gets slaughtered during a deploy, raid timers don't pile up simultaneously on boot, and headless `samgenai` runs use the exact same `sim_clock_ms` representation without touching the OS wall clock!
