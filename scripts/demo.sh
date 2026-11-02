#!/usr/bin/env bash
# Walks a running service through the behaviour the README describes and prints each
# request and response. Start the service first: ./gradlew bootTestRun --args='--server.port=18080'
set -euo pipefail
BASE="${1:-http://localhost:18080}"
RUN="$(date +%s)"

call() { # method path [key] [body]
  local method="$1" path="$2" key="${3:-}" body="${4:-}"
  local args=(-s -X "$method" "$BASE$path" -H 'Content-Type: application/json' -w '\n%{http_code}')
  [ -n "$key" ] && args+=(-H "Idempotency-Key: $key")
  [ -n "$body" ] && args+=(-d "$body")
  curl "${args[@]}"
}

show() { # title jq-filter method path [key] [body]
  local title="$1" filter="$2"
  shift 2
  local reply status
  reply="$(call "$@")"
  status="$(tail -n1 <<<"$reply")"
  echo "--- $title"
  echo "$1 $2  ->  $status"
  sed '$d' <<<"$reply" | jq -c "$filter"
  echo
}

open_account() { # code normalSide overdraftAllowed
  call POST /v1/accounts "" "{\"code\":\"$1-$RUN\",\"name\":\"$1\",\"currency\":\"USD\",\"normalSide\":\"$2\",\"overdraftAllowed\":$3}" \
    | sed '$d' | jq -r .id
}

entry() { # debitAccount creditAccount amountMinor description
  echo "{\"currency\":\"USD\",\"description\":\"$4\",\"postings\":[{\"accountId\":\"$1\",\"direction\":\"DEBIT\",\"amountMinor\":$3},{\"accountId\":\"$2\",\"direction\":\"CREDIT\",\"amountMinor\":$3}]}"
}

CASH="$(open_account cash DEBIT true)"
ALICE="$(open_account alice CREDIT false)"
BOB="$(open_account bob CREDIT false)"
SUMMARY='{id, sequence, replayed, postings: [.postings[] | {direction, amountMinor, balanceAfterMinor}]}'

show "Deposit 100.00 into Alice" "$SUMMARY" POST /v1/entries "dep-$RUN" "$(entry "$CASH" "$ALICE" 10000 'Deposit')"
show "Alice pays Bob 25.50" "$SUMMARY" POST /v1/entries "pay-$RUN" "$(entry "$ALICE" "$BOB" 2550 'Invoice 7')"
show "The client retries after a timeout: same key, same body" "$SUMMARY" POST /v1/entries "pay-$RUN" "$(entry "$ALICE" "$BOB" 2550 'Invoice 7')"
show "Same key, different amount" '{code, detail}' POST /v1/entries "pay-$RUN" "$(entry "$ALICE" "$BOB" 2551 'Invoice 7')"
show "Alice tries to spend more than she holds" '{code, detail}' POST /v1/entries "big-$RUN" "$(entry "$ALICE" "$BOB" 999999 'Too much')"
show "A decimal amount is refused, not truncated" '{status, detail}' POST /v1/entries "dec-$RUN" "$(entry "$ALICE" "$BOB" 10.5 'Half a cent')"
PAY_ID="$(call POST /v1/entries "pay-$RUN" "$(entry "$ALICE" "$BOB" 2550 'Invoice 7')" | sed '$d' | jq -r .id)"
show "Correct the payment by reversing it" "$SUMMARY" POST "/v1/entries/$PAY_ID/reversal" "undo-$RUN"
show "Reverse it again with a new key" '{code, detail}' POST "/v1/entries/$PAY_ID/reversal" "undo2-$RUN"
show "Alice's statement, newest first" '[.lines[] | {description, direction, amountMinor, balanceAfterMinor}]' GET "/v1/accounts/$ALICE/statement"
show "Alice's balance" '{balanceMinor, balanceDisplay}' GET "/v1/accounts/$ALICE"
show "Audit: recompute everything from the journal" '{clean, entries, postings, trialBalance, driftedAccounts, runningBalanceBreaks, overdrawnAccounts, outboxPending}' GET /v1/audit
