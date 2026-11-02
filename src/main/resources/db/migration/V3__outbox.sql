-- next_attempt_at doubles as the lease and the retry backoff: claiming a row pushes it
-- into the future, so a relay that dies mid-batch only delays delivery until it expires.
CREATE TABLE outbox_events (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id        uuid        NOT NULL UNIQUE,
    entry_id        uuid        NOT NULL REFERENCES journal_entries (id),
    event_type      text        NOT NULL,
    payload         jsonb       NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    last_error      text
);

CREATE INDEX outbox_events_pending_idx ON outbox_events (next_attempt_at, id) WHERE published_at IS NULL;
