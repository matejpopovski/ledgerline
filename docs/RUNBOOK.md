# ledgerline runbook

Operational notes for whoever is holding the pager. Every `sql` block in this file is
executed against a populated ledger by `RunbookQueriesTest`, so a query here cannot quietly
stop matching the schema. In the blocks, `:account_id` and `:entry_id` are placeholders.

## What to watch

| Signal | Where | Healthy | Act when |
| --- | --- | --- | --- |
| Books agree with the journal | `GET /v1/audit`, field `clean` | `true` | Any `false`: go to [The audit is not clean](#the-audit-is-not-clean) |
| Outbox backlog | `outboxPending`, `oldestPendingSeconds` in the same report | Age near the poll interval | Age keeps growing: go to [Events are not arriving](#events-are-not-arriving) |
| Retries | `ledger.transaction.retries`, tagged `sqlstate` | `40001` only, and only with a retrying strategy | Any `40P01` (see [Deadlocks](#deadlocks)), or `55P03` trending up |
| Giving up | `ledger.transaction.exhausted` | Zero | Any sustained rate: clients are getting 503 |
| Replays | `ledger.entries`, tag `outcome=replayed` | A small share of `posted` | A jump usually means a client timeout is set too low |
| Delivery | `ledger.outbox.delivered`, `ledger.outbox.failed` | No failures | Failures with no deliveries means the receiver is down |

The audit scans `postings` in full. Schedule it, say hourly, against a replica if one exists. It
reads from one REPEATABLE READ snapshot, so it does not block writers and every number in a report
describes the same instant, but that snapshot holds back vacuum for as long as it runs.

## The audit is not clean

Stop and read the report first. Nothing here is fixed by editing the journal, and the triggers
will refuse if you try.

**`driftedAccounts` is not empty.** The stored balance disagrees with the sum of the journal. The
journal is the truth and the stored balance is a cache. Find where the running balance first
went wrong:

```sql
SELECT id, entry_id, direction, amount_minor, balance_after_minor, expected_after
FROM (
    SELECT p.id, p.entry_id, p.direction, p.amount_minor, p.balance_after_minor,
           lag(p.balance_after_minor, 1, 0::bigint) OVER (ORDER BY p.id)
               + CASE WHEN p.direction = a.normal_side THEN p.amount_minor
                      ELSE -p.amount_minor END AS expected_after
    FROM postings p
    JOIN accounts a ON a.id = p.account_id
    WHERE p.account_id = :account_id) chain
WHERE balance_after_minor <> expected_after
ORDER BY id
LIMIT 5
```

No rows means the chain is intact and only the cached balance is wrong, which points at something
writing to `accounts` outside the service. Rows mean a posting was computed from a stale balance:
a lost update. That would be a concurrency bug, so treat it as an incident and find the deploy
that introduced it before touching anything.

Only after the cause is gone, restore the cache from the journal:

```sql
UPDATE accounts a
SET balance_minor = coalesce((
    SELECT sum(CASE WHEN p.direction = a.normal_side THEN p.amount_minor
                    ELSE -p.amount_minor END)
    FROM postings p WHERE p.account_id = a.id), 0)
WHERE a.id = :account_id
```

**`unbalancedEntries` or a trial balance that differs.** The commit trigger makes this impossible
without disabling triggers, so assume somebody did. Check who has owner rights on the tables.

**`overdrawnAccounts` is positive.** The journal itself says a no-overdraft account is negative.
Same conclusion as a lost update. Correct it with a new entry, reviewed by a person; do not
reverse entries in bulk.

## Clients are getting 503 contention_limit

A request used all of its 12 attempts. Find out what the writers are queueing behind:

```sql
SELECT waiting.pid AS waiting_pid,
       blocking.pid AS blocking_pid,
       blocking.state AS blocking_state,
       now() - blocking.xact_start AS blocking_for,
       left(blocking.query, 80) AS blocking_query
FROM pg_stat_activity waiting
JOIN LATERAL unnest(pg_blocking_pids(waiting.pid)) AS holder(pid) ON true
JOIN pg_stat_activity blocking ON blocking.pid = holder.pid
ORDER BY blocking_for DESC
```

- A blocker `idle in transaction` for seconds is a stuck session, not load. The service sets
  `idle_in_transaction_session_timeout` to 15 s on its own connections; a human psql session has
  no such limit.
- If the same account appears everywhere, it is a hot account. A row lock is held through the
  commit flush, so one account can take about one write per commit latency no matter how many
  workers are added. `results/lock-strategies.json` shows the curve. Adding pool connections
  makes the queue longer, not faster.

To see which accounts are hot:

```sql
SELECT a.code, count(*) AS postings_last_hour
FROM postings p
JOIN journal_entries e ON e.id = p.entry_id
JOIN accounts a ON a.id = p.account_id
WHERE e.posted_at > now() - interval '1 hour'
GROUP BY a.code
ORDER BY postings_last_hour DESC
LIMIT 10
```

## Deadlocks

`40P01` should never appear: every writer takes account rows in id order, and
`opposingTransfersDoNotDeadlock` holds that in place. The retry loop will hide a deadlock from
clients at the cost of a one-second `deadlock_timeout` stall, so the metric is the only symptom.
A nonzero count means something else is locking `accounts`: look for a migration or an operator
session.

## Events are not arriving

The ledger keeps working while the receiver is down; events wait in `outbox_events`. See what is
stuck and why:

```sql
SELECT id, event_id, entry_id, attempts, next_attempt_at, last_error
FROM outbox_events
WHERE published_at IS NULL
ORDER BY id
LIMIT 20
```

Backoff doubles from 2 s up to 5 minutes and never gives up, because dropping an event is worse
than being noisy. Once the receiver is back, bring everything forward instead of waiting out the
backoff:

```sql
UPDATE outbox_events
SET next_attempt_at = now()
WHERE published_at IS NULL
```

Delivery is at least once. A relay that dies after the receiver accepted an event and before
`published_at` was written will send it again when the 30 s lease expires, so receivers must
deduplicate on `Ledger-Event-Id`. Events can also arrive out of order after a retry. A receiver
that needs account order should compare `sequence` or `balanceAfterMinor`. Note that `sequence`
has gaps: a refused request burns an identity value when it rolls back, so a gap means nothing.

If `ledger.outbox.webhook-url` is blank the relay does not run and the table only grows.

## A wrong entry was posted

Post its reversal, which is the only correction there is:

```
POST /v1/entries/{id}/reversal
Idempotency-Key: <new key>
```

- 409 `already_reversed`: it has been done; read the journal to see by whom.
- 422 `insufficient_funds`: the money has moved on. The reversal would overdraw an account, so
  this needs a decision from a person and usually a fresh entry against a different account.

To see everything related to an entry:

```sql
SELECT e.seq, e.id, e.description, e.reverses_entry_id, e.posted_at, p.account_id, p.direction,
       p.amount_minor
FROM journal_entries e
JOIN postings p ON p.entry_id = e.id
WHERE e.id = :entry_id OR e.reverses_entry_id = :entry_id
ORDER BY e.seq, p.id
```

## A client reports 409 idempotency_key_reused

The key was used before with a different body. Either the client is generating keys badly, or it
changed the request while retrying, and the ledger cannot tell which was meant. Nothing moved on
the second request. Keys never expire: there is no window after which a replay becomes a new
payment.

## Changing the concurrency strategy

`ledger.strategy` takes `ROW_LOCK`, `SERIALIZABLE` or `REPEATABLE_READ` and needs a restart. No
migration is involved, and a rolling restart with instances on different strategies keeps the
invariants: `mixedStrategiesDuringARollingChangeKeepTheBooks` runs that case.

## Schema changes

Migrations run through Flyway at startup. Whoever owns the tables can run
`ALTER TABLE ... DISABLE TRIGGER`, so the append-only guarantee is only as strong as that role.
In a real deployment the service should connect as a role that does not own the tables; this
repository does not set that up. Never run a migration with `session_replication_role = replica`,
which silently skips every guard, and after any migration touching these tables run the audit.

## Keeping hot rows healthy

`accounts` is updated on every posting and created with `fillfactor = 50` so those updates can
stay HOT. If throughput decays over hours, check that autovacuum is keeping up:

```sql
SELECT relname, n_live_tup, n_dead_tup, n_tup_hot_upd, n_tup_upd, last_autovacuum
FROM pg_stat_user_tables
WHERE relname IN ('accounts', 'outbox_events')
```

`outbox_events` is never pruned by the service. Published rows are safe to delete, and the
journal does not depend on them:

```sql
DELETE FROM outbox_events
WHERE published_at < now() - interval '30 days'
```
