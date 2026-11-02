-- Money is stored as BIGINT minor units (cents, yen, fils). The bound is 2^53 - 1 so a
-- JSON number survives a round trip through any client that parses into an IEEE double.

CREATE TABLE accounts (
    id                uuid        PRIMARY KEY,
    code              text        NOT NULL,
    name              text        NOT NULL,
    currency          char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    normal_side       char(1)     NOT NULL CHECK (normal_side IN ('D', 'C')),
    overdraft_allowed boolean     NOT NULL DEFAULT false,
    balance_minor     bigint      NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT accounts_code_key UNIQUE (code),
    CONSTRAINT accounts_id_currency_key UNIQUE (id, currency),
    CONSTRAINT accounts_no_overdraft CHECK (overdraft_allowed OR balance_minor >= 0),
    CONSTRAINT accounts_balance_range CHECK (balance_minor BETWEEN -9007199254740991 AND 9007199254740991)
) WITH (fillfactor = 50);
-- balance_minor is in no index, so the free space above lets the hottest rows in the
-- system take HOT updates instead of growing every index on each posting.

CREATE TABLE journal_entries (
    id                uuid        PRIMARY KEY,
    seq               bigint      GENERATED ALWAYS AS IDENTITY,
    idempotency_key   text        NOT NULL,
    fingerprint       char(64)    NOT NULL,
    currency          char(3)     NOT NULL,
    description       text        NOT NULL,
    reverses_entry_id uuid        REFERENCES journal_entries (id),
    posted_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT journal_entries_seq_key UNIQUE (seq),
    CONSTRAINT journal_entries_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT journal_entries_reverses_once UNIQUE (reverses_entry_id),
    CONSTRAINT journal_entries_id_currency_key UNIQUE (id, currency),
    CONSTRAINT journal_entries_not_self_reversing CHECK (reverses_entry_id <> id)
);

-- The two composite foreign keys make a mixed-currency entry unrepresentable: a posting
-- must carry the currency of its entry and of its account.
CREATE TABLE postings (
    id                  bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id            uuid    NOT NULL,
    account_id          uuid    NOT NULL,
    currency            char(3) NOT NULL,
    direction           char(1) NOT NULL CHECK (direction IN ('D', 'C')),
    amount_minor        bigint  NOT NULL CHECK (amount_minor BETWEEN 1 AND 9007199254740991),
    balance_after_minor bigint  NOT NULL,
    CONSTRAINT postings_entry_fk FOREIGN KEY (entry_id, currency) REFERENCES journal_entries (id, currency),
    CONSTRAINT postings_account_fk FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency),
    CONSTRAINT postings_entry_account_key UNIQUE (entry_id, account_id)
);

CREATE INDEX postings_account_statement_idx ON postings (account_id, id);
