-- Ticket 08 V002: wilds_meta schema — permanent neutral seats and the version-pinned world.
CREATE SCHEMA IF NOT EXISTS wilds_meta;

CREATE TABLE wilds_meta.seats (
    seat_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id   UUID NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    seat_name       TEXT NOT NULL UNIQUE CHECK (char_length(seat_name) BETWEEN 3 AND 40),
    controller_kind TEXT NOT NULL DEFAULT 'HUMAN'
                    CHECK (controller_kind IN ('HUMAN', 'SCRIPT_BOT', 'GENETIC_AGENT')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_seats_owner ON wilds_meta.seats (owner_user_id);

CREATE TABLE wilds_meta.worlds (
    world_id         INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title            TEXT NOT NULL,
    world_seed       BIGINT NOT NULL,
    rules_version    INT NOT NULL,
    engine_version   INT NOT NULL,
    protocol_version INT NOT NULL,
    jvm_version      TEXT NOT NULL,
    config_json      JSONB NOT NULL DEFAULT '{}'::jsonb,
    status           TEXT NOT NULL DEFAULT 'ACTIVE'
                     CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at         TIMESTAMPTZ
);

-- At most one ACTIVE world at any time (partial unique index on a constant).
CREATE UNIQUE INDEX uq_worlds_one_active ON wilds_meta.worlds ((1)) WHERE status = 'ACTIVE';
