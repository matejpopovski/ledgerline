package io.github.matejpopovski.ledgerline.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the journal says about itself at one consistent point in time. */
public record AuditReport(
    Instant takenAt,
    long entries,
    long postings,
    List<CurrencyTotal> trialBalance,
    long unbalancedEntries,
    List<AccountDrift> driftedAccounts,
    long runningBalanceBreaks,
    long overdrawnAccounts,
    long outboxPending,
    Long oldestPendingSeconds) {

  public AuditReport {
    trialBalance = List.copyOf(trialBalance);
    driftedAccounts = List.copyOf(driftedAccounts);
  }

  /** Total debits and credits for one currency, added up from postings. */
  public record CurrencyTotal(String currency, long debitsMinor, long creditsMinor) {}

  /** An account whose stored balance disagrees with its journal. */
  public record AccountDrift(UUID accountId, String code, long storedMinor, long journalMinor) {}

  /** True when every ledger invariant holds. The outbox backlog is not an invariant. */
  @JsonProperty("clean")
  public boolean clean() {
    return unbalancedEntries == 0
        && driftedAccounts.isEmpty()
        && runningBalanceBreaks == 0
        && overdrawnAccounts == 0
        && trialBalance.stream().allMatch(t -> t.debitsMinor() == t.creditsMinor());
  }
}
