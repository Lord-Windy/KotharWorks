-- Ticket 08 V003: wilds_world schema — 100% append-only WAL and event log, bounded checkpoints.
-- Zero UPDATE semantics: rows are only ever INSERTed; Commit-Before-Mutate needs no mutable
-- entity tables anywhere in this schema.
CREATE SCHEMA IF NOT EXISTS wilds_world;

CREATE TABLE wilds_world.command_wal (
    world_id     INT NOT NULL,
    wal_seq      BIGINT GENERATED ALWAYS AS IDENTITY,
    admitted_ms  BIGINT NOT NULL,
    seat_id      BIGINT,
    cmd_id       UUID,
    cmd_type     TEXT NOT NULL,
    payload_json JSONB NOT NULL,
    world_hash   BIGINT,
    recorded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, wal_seq),
    UNIQUE (world_id, cmd_id),
    CHECK ((cmd_type = 'CLOCK_ADVANCE' AND seat_id IS NULL AND cmd_id IS NULL)
        OR (cmd_type <> 'CLOCK_ADVANCE' AND seat_id IS NOT NULL AND cmd_id IS NOT NULL))
);

CREATE TABLE wilds_world.world_checkpoints (
    world_id       INT NOT NULL,
    checkpoint_id  BIGINT GENERATED ALWAYS AS IDENTITY,
    up_to_wal_seq  BIGINT NOT NULL,
    logical_ms     BIGINT NOT NULL,
    world_hash     BIGINT NOT NULL,
    state_json     JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, checkpoint_id)
);

CREATE TABLE wilds_world.world_events (
    world_id        INT NOT NULL,
    event_cursor    BIGINT NOT NULL,
    occurred_ms     BIGINT NOT NULL,
    kind            TEXT NOT NULL,
    seat_id         BIGINT,
    public_summary  TEXT,
    private_summary TEXT NOT NULL,
    payload_json    JSONB NOT NULL,
    PRIMARY KEY (world_id, event_cursor)
);
