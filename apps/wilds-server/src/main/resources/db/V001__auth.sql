-- Ticket 08 V001: auth schema — accounts, identity links, web sessions, scoped API tokens.
-- Gameplay-neutral: no passwords, no gameplay nouns; credentials live only as SHA-256 digests.
CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE auth.users (
    user_id            UUID PRIMARY KEY,
    display_name       TEXT NOT NULL,
    max_seats_override INT,
    suspended_at       TIMESTAMPTZ,
    suspended_reason   TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth.identity_links (
    issuer    TEXT NOT NULL,
    subject   TEXT NOT NULL,
    user_id   UUID NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    linked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issuer, subject)
);

CREATE INDEX idx_identity_links_user ON auth.identity_links (user_id);

CREATE TABLE auth.web_sessions (
    session_sha256     BYTEA PRIMARY KEY CHECK (octet_length(session_sha256) = 32),
    user_id            UUID NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    issuer             TEXT NOT NULL,
    subject            TEXT NOT NULL,
    granted_roles      TEXT[] NOT NULL,
    csrf_token         TEXT NOT NULL,
    roles_refreshed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at         TIMESTAMPTZ NOT NULL,
    last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_web_sessions_user ON auth.web_sessions (user_id);

CREATE TABLE auth.api_tokens (
    token_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_user_id      UUID NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    resource_namespace TEXT NOT NULL DEFAULT 'wilds:seat',
    scoped_resource_id BIGINT NOT NULL,
    token_prefix       CHAR(8) NOT NULL,
    token_sha256       BYTEA NOT NULL UNIQUE CHECK (octet_length(token_sha256) = 32),
    label              TEXT NOT NULL,
    rate_per_sec       INT NOT NULL DEFAULT 10,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_used_at       TIMESTAMPTZ,
    revoked_at         TIMESTAMPTZ
);

CREATE INDEX idx_api_tokens_owner ON auth.api_tokens (owner_user_id);
CREATE INDEX idx_api_tokens_resource ON auth.api_tokens (resource_namespace, scoped_resource_id);
