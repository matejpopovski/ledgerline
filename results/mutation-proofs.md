| Mutant | Rule | Protection removed | Outcome | Tests failed | Race trials |
| --- | --- | --- | --- | ---: | ---: |
| `conservation-no-domain-check` | conservation | Debits-equal-credits check in EntryDraft (the database trigger stays) | killed | 3 |  |
| `conservation-no-db-trigger` | conservation | Deferred balance trigger on journal_entries and postings (the domain check stays) | killed | 3 |  |
| `conservation-no-check-at-all` | conservation | Both balance checks at once: an unbalanced entry reaches the journal | killed | 6 |  |
| `conservation-sign-flip` | conservation | Normal-side logic in Account.balanceAfter: a debit raises a credit-normal balance | killed | 86 |  |
| `conservation-balance-write-skipped` | conservation | The stored balance is not written back, so it drifts from the journal | killed | 63 |  |
| `audit-without-snapshot` | conservation | REPEATABLE READ on the audit: queries see different instants | killed | 1 | 3 of 3 |
| `idempotency-none` | idempotent posting | Key uniqueness: every request is stored under a fresh key | killed | 20 |  |
| `idempotency-check-then-insert` | idempotent posting | The unique index: the key is only checked by a SELECT before inserting, which passes every sequential test | killed | 2 | 3 of 3 |
| `idempotency-claim-after-funds-check` | idempotent posting | Claim-first ordering: funds are checked before the key, so a replay can be answered with a false overdraft | killed | 14 |  |
| `idempotency-no-fingerprint` | idempotent posting | Fingerprint comparison: a reused key silently returns the old entry for a different request | killed | 3 |  |
| `lock-row-lock-removed` | no lost update | FOR NO KEY UPDATE on the account rows under READ COMMITTED | killed | 8 | 3 of 3 |
| `lock-order-removed` | no lost update | Canonical lock order: rows are locked one at a time in request order | killed | 2 | 3 of 3 |
| `serializable-downgraded` | no lost update | SERIALIZABLE isolation: the strategy runs at READ COMMITTED with no locks | killed | 8 | 3 of 3 |
| `retry-removed` | no lost update | The retry loop: a serialization failure reaches the caller | killed | 14 |  |
| `overdraft-no-domain-check` | no overdraft | Funds check in Account.balanceAfter (the CHECK constraint stays) | killed | 13 |  |
| `overdraft-no-db-check` | no overdraft | The accounts_no_overdraft CHECK constraint (the domain check stays) | killed | 1 |  |
| `overdraft-no-check-at-all` | no overdraft | Both overdraft checks at once: accounts really go negative | killed | 19 |  |
| `immutable-entries-unguarded` | immutable journal | Append-only triggers on journal_entries | killed | 3 |  |
| `immutable-postings-unguarded` | immutable journal | Append-only triggers on postings | killed | 4 |  |
| `immutable-reversal-not-unique` | immutable journal | The one-reversal-per-entry unique constraint | killed | 6 |  |
| `immutable-account-identity` | immutable journal | Trigger keeping an account's currency and normal side fixed | killed | 1 |  |
| `money-decimal-truncated` | exact money | Strict number parsing: Jackson truncates 10.5 to 10 on a long field | killed | 1 |  |
| `money-string-coerced` | exact money | Scalar coercion off: the string "100" is accepted as an amount | killed | 1 |  |
| `money-currency-fk-removed` | exact money | Composite foreign key tying a posting to its account currency | killed | 1 |  |
| `timeouts-not-configured` | bounded waiting | Connection init SQL: no lock_timeout, so a stuck row holder blocks callers indefinitely | killed | 1 |  |
| `outbox-dual-write` | outbox atomicity | Same-transaction outbox insert: the event is written after the entry commits | killed | 1 |  |
| `outbox-no-lease` | outbox delivery | The claim lease: a claimed event is immediately claimable again | killed | 2 |  |
| `outbox-failure-marked-published` | outbox delivery | Failure handling: an event is marked published even though the sink refused it | killed | 1 |  |
| `outbox-skip-locked-removed` | outbox delivery | SKIP LOCKED: relays wait for each other | survived | 0 |  |
