# Ticket 08 — PostgreSQL 3-Schema Persistence, SqlMigrator, JDBI Adapters & Crash Recovery

- **`st` Key:** `08da943e` (`KOTHARWORKS` · `tech-wilds`)
- **Status:** Backlog *(auto-promotes when `9ba362ce` completes)*
- **Depends on:** Ticket `07` (`9ba362ce`)
- **Modules touched:** Root `pom.xml`, `PROJECTS.md`, `apps/wilds-server`

---

## 1. Objective

Implement the production PostgreSQL persistence layer across three strict schemas (`auth`, `wilds_meta`, `wilds_world`) in `apps/wilds-server`:
1. **Zero-Framework Forward `SqlMigrator` (`~100 LOC`)** applying plain versioned `.sql` scripts.
2. **Gameplay-Neutral Three-Schema DDL:** Zero passwords in `auth`; permanent neutral `seats` & version-pinned `worlds` in `wilds_meta`; **100% append-only** `command_wal`, `world_checkpoints`, and `world_events` in `wilds_world` (zero mutable entity `UPDATE` tables).
3. **HikariCP + JDBI3 Adapters** (`PostgresAuthStore`, `PostgresWorldStore`).
4. **`PAUSE_WHILE_DOWN` Startup & Crash Recovery:** Checkpoint verification (`WorldHash`) + `command_wal` tail replay + idempotent event backfill + graceful `SIGTERM` checkpointing, proven by **Testcontainers PostgreSQL** integration tests.

---

## 2. Explicit Non-Goals

- Do **not** add Hibernate, JPA, Spring Data, Flyway, or Liquibase.
- Do **not** add gameplay tables or columns (`chronicle_standings.raids_repelled`, `doctrine_blueprints`, `battle_chronicles`); those will be added via forward `V004+` SQL migrations once gameplay settles.

---

## 3. Deliverables & Structure

### 3.1 Dependencies (Pinned in Root `pom.xml` `<dependencyManagement>`)
- Runtime in `apps/wilds-server`: `org.postgresql:postgresql:42.7.14`, `com.zaxxer:HikariCP:7.1.0`, `org.jdbi:jdbi3-core:3.55.0`, `org.slf4j:slf4j-simple:2.0.20` (`runtime` scope so HikariCP/Testcontainers emit clean logs without missing-provider warnings).
- Test in `apps/wilds-server`: **Testcontainers 2.x** — `org.testcontainers:testcontainers-junit-jupiter:2.0.5` and `org.testcontainers:testcontainers-postgresql:2.0.5` (`org.testcontainers.postgresql.PostgreSQLContainer` — required for Docker Engine 29+ API `>= 1.40` compatibility and zero `[rawtypes]` warnings under `-Xlint:all,-serial -Werror`!).

### 3.2 Forward `SqlMigrator` & Plain SQL Migrations (`src/main/resources/db/`)
- **`SqlMigrator.java`:** Creates `public.schema_migrations (version INT PRIMARY KEY, filename TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`, locks safely via `pg_advisory_lock` / transaction, applies unapplied `V###__*.sql` resources in ascending numeric order, and **refuses to boot** (`IllegalStateException`) if the database contains a higher migration version than the binary knows about.
- **`V001__auth.sql`:**
  - `auth.users` (`user_id UUID PK`, `display_name TEXT`, `max_seats_override INT`, `suspended_at TIMESTAMPTZ`, `suspended_reason TEXT`, `created_at`, `last_seen_at`)
  - `auth.identity_links` (`(issuer, subject) PK -> user_id UUID`)
  - `auth.web_sessions` (`session_sha256 BYTEA PK CHECK (octet_length = 32)`, `user_id UUID`, `issuer`, `subject`, `granted_roles TEXT[]`, `csrf_token TEXT`, `expires_at`, ...)
  - `auth.api_tokens` (`token_id BIGINT IDENTITY PK`, `owner_user_id UUID`, `resource_namespace TEXT`, `scoped_resource_id BIGINT`, `token_prefix CHAR(8)`, `token_sha256 BYTEA UNIQUE CHECK (octet_length = 32)`, `label TEXT`, `rate_per_sec INT`, `revoked_at`, ...)
- **`V002__wilds_meta.sql`:**
  - `wilds_meta.seats` (`seat_id BIGINT IDENTITY PK`, `owner_user_id UUID REFERENCES auth.users`, `seat_name TEXT UNIQUE CHECK (char_length BETWEEN 3 AND 40)`, `controller_kind TEXT CHECK IN ('HUMAN', 'SCRIPT_BOT', 'GENETIC_AGENT')`, `created_at`)
  - `wilds_meta.worlds` (`world_id INT IDENTITY PK`, `title TEXT`, `world_seed BIGINT`, `rules_version INT`, `engine_version INT`, `protocol_version INT`, `jvm_version TEXT`, `config_json JSONB`, `status TEXT CHECK IN ('ACTIVE', 'ARCHIVED')`, `started_at`, `ended_at`, plus partial unique index `WHERE status = 'ACTIVE'` enforcing at most one active world)
- **`V003__wilds_world.sql` (Zero `UPDATE`s — Append-Only WAL & Events + Bounded Checkpoints):**
  - `wilds_world.command_wal` (`world_id INT`, `wal_seq BIGINT IDENTITY`, `admitted_ms BIGINT`, `seat_id BIGINT NULL`, `cmd_id UUID NULL`, `cmd_type TEXT`, `payload_json JSONB`, `world_hash BIGINT NULL`, `PRIMARY KEY (world_id, wal_seq)`, `UNIQUE (world_id, cmd_id)`, `CHECK ((cmd_type = 'CLOCK_ADVANCE' AND seat_id IS NULL AND cmd_id IS NULL) OR (cmd_type <> 'CLOCK_ADVANCE' AND seat_id IS NOT NULL AND cmd_id IS NOT NULL))`)
  - `wilds_world.world_checkpoints` (`world_id INT`, `checkpoint_id BIGINT IDENTITY`, `up_to_wal_seq BIGINT`, `logical_ms BIGINT`, `world_hash BIGINT`, `state_json JSONB`, `PRIMARY KEY (world_id, checkpoint_id)`)
  - `wilds_world.world_events` (`world_id INT`, `event_cursor BIGINT`, `occurred_ms BIGINT`, `kind TEXT`, `seat_id BIGINT NULL`, `public_summary TEXT NULL`, `private_summary TEXT NOT NULL`, `payload_json JSONB`, `PRIMARY KEY (world_id, event_cursor)`)

### 3.3 JDBI Adapters, Recovery & CLI Wiring (`adapters/postgres/` & `Main`)
- **`PostgresAuthStore` & `PostgresWorldStore`:** Clean SQL via JDBI3 Handle API:
  - For concurrent JIT `(issuer, subject) -> UserId` provisioning, take `pg_advisory_xact_lock(hashtextextended(issuer || ':' || subject, 0))` inside the transaction before checking `auth.identity_links` and inserting `auth.users` + `auth.identity_links` — guaranteeing zero orphan `auth.users` rows and zero `23505` races under concurrent logins!
  - Use `INSERT ... ON CONFLICT (world_id, event_cursor) DO NOTHING` for idempotent event backfills.
- **`WorldHost` Recovery & Lifecycle:**
  1. **Boot Recovery:** Verify active `wilds_meta.worlds` `(rules_version, engine_version)` matches running binary. Load latest `world_checkpoints` row if present (decoding `WorldState` — including `nextEventSeq` and `nextEventCursor` — and **asserting `engine.worldHash() == checkpoint.worldHash()`**; if no checkpoint exists yet, initialize a fresh seeded `WildsEngine` with `up_to_wal_seq = 0L`), then replay all `command_wal` rows with `wal_seq > up_to_wal_seq` in ascending `wal_seq` order (populating `WorldHost`'s `cmdId` receipt cache and re-backfilling any missing `world_events` rows via `ON CONFLICT DO NOTHING`).
  2. **`PAUSE_WHILE_DOWN` Clock Resume:** Initialize `logicalClock` at recovered `engine.nowMs()` so downtime never advances unmonitored world time!
  3. **Checkpointing:** Write a `world_checkpoints` row every `N` minutes of uptime (configurable, e.g. 5m) **and** on graceful `SIGTERM` / `Server.close()`, deleting checkpoint rows older than the latest 10.
  4. **WAL Export Helper:** `exportWalJsonl(int worldId, Writer out)` writing `WAL_HEADER` + `WalRecord` lines for `apps/wilds-headless replay`.
- Update `Main` CLI flags: `--bind=127.0.0.1`, `--port=8080`, `--time-scale=1`, `--storage=memory|postgres`, `--jdbc-url=...`, `--db-user=...`, `--db-password=...`, `--auth-mode=dev-local|oidc`.

---

## 4. Tests & Acceptance Criteria (`SharedPostgresContainer` Testcontainers Suite)

Use a **singleton `org.testcontainers.postgresql.PostgreSQLContainer("postgres:17-alpine")`** started once per test JVM so the entire Postgres suite runs in a couple of seconds:
1. **`SqlMigratorTest`:** Fresh schema creation, idempotent second boot (0 migrations re-run), and hard failure when an unknown `V999` row is inserted into `public.schema_migrations`.
2. **Concurrent JIT Identity Link Test:** 20 Virtual Threads simultaneously authenticating the same brand-new `(issuer, subject)` mint **exactly one** `auth.users` UUID without unique-constraint races.
3. **DB Error Split-Brain Immunity Test:** Simulate a broken connection / constraint failure during `appendCommand` -> verify `WorldHost` returns `503` and `WorldState` in RAM (`worldHash`) does **not** advance ahead of `command_wal`.
4. **`kill -9` Crash Recovery & Zero SSE Cursor Rewind Test:**
   - Start server against Postgres, charter seat, write a checkpoint at `wal_seq = K`, then enqueue `Travel` + `Wait` and let passive `CLOCK_ADVANCE` complete the tasks and emit events up to `cursor = M` **without** writing a new checkpoint (simulating an abrupt `kill -9`!).
   - Discard the server instance in memory (without calling graceful checkpoint) and boot a brand-new `WorldHost` against the same Postgres database.
   - Assert recovered `engine.nowMs()`, `engine.worldHash()`, actor location, and highest event `cursor == M` match the pre-crash state **100% identically** (zero cursor rewind, zero lost progress, zero offline jump during downtime!).
