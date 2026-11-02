package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertAuditClean;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.audit.AuditReport;
import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.Race;
import io.github.matejpopovski.ledgerline.support.Race.Outcome;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The audit is the alarm for everything above, so it must fire and must not fire falsely. */
class AuditTest {

  @Test
  void anEmptyLedgerIsClean() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      assertAuditClean(ledger);
    }
  }

  @Test
  void aStoredBalanceEditedBehindTheServiceIsReportedAsDrift() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);
      ledger
          .jdbc
          .sql("UPDATE accounts SET balance_minor = 90 WHERE id = :id")
          .param("id", wallet.id())
          .update();

      AuditReport report = ledger.auditReport();

      assertThat(report.clean()).isFalse();
      assertThat(report.driftedAccounts())
          .singleElement()
          .satisfies(
              drift -> {
                assertThat(drift.accountId()).isEqualTo(wallet.id());
                assertThat(drift.storedMinor()).isEqualTo(90);
                assertThat(drift.journalMinor()).isEqualTo(100);
              });
    }
  }

  @Test
  void aBrokenRunningBalanceIsReported() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);
      ledger.jdbc.sql("ALTER TABLE postings DISABLE TRIGGER USER").update();
      ledger
          .jdbc
          .sql("UPDATE postings SET balance_after_minor = 999 WHERE account_id = :id")
          .param("id", wallet.id())
          .update();
      ledger.jdbc.sql("ALTER TABLE postings ENABLE TRIGGER USER").update();

      assertThat(ledger.auditReport().runningBalanceBreaks()).isPositive();
    }
  }

  @Test
  void anAccountOverdrawnInTheJournalIsReported() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);
      ledger.jdbc.sql("ALTER TABLE postings DISABLE TRIGGER USER").update();
      ledger
          .jdbc
          .sql(
              """
              UPDATE postings SET direction = 'D'
              WHERE account_id = :id
              """)
          .param("id", wallet.id())
          .update();
      ledger.jdbc.sql("ALTER TABLE postings ENABLE TRIGGER USER").update();

      AuditReport report = ledger.auditReport();

      assertThat(report.overdrawnAccounts()).isEqualTo(1);
      assertThat(report.unbalancedEntries()).isEqualTo(1);
      assertThat(report.clean()).isFalse();
    }
  }

  /**
   * Each comparison in the audit is one statement, so it cannot disagree with itself. What needs
   * the shared snapshot is the report as a whole: here every entry has two legs of known size, so
   * its counts and totals only line up if they all describe the same instant.
   */
  @Test
  void oneReportDescribesOneInstantDuringConcurrentPosting() {
    long opening = 1_000_000;
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account a = ledger.fundedWallet("USD", opening);
      Account b = ledger.wallet("USD");
      AtomicInteger writersLeft = new AtomicInteger(4);

      List<Outcome<Integer>> outcomes =
          Race.run(
              8,
              racer -> {
                if (racer < 4) {
                  try {
                    for (int i = 0; i < 300; i++) {
                      ledger.move(UUID.randomUUID().toString(), a, b, 1);
                    }
                  } finally {
                    writersLeft.decrementAndGet();
                  }
                  return 0;
                }
                int reports = 0;
                while (writersLeft.get() > 0) {
                  AuditReport report = ledger.auditReport();
                  assertThat(report.clean()).isTrue();
                  assertThat(report.postings()).isEqualTo(2 * report.entries());
                  assertThat(report.trialBalance().getFirst().debitsMinor())
                      .isEqualTo(opening + report.entries() - 1);
                  reports++;
                }
                return reports;
              });

      assertThat(outcomes).allMatch(Outcome::succeeded);
      assertThat(outcomes.stream().mapToInt(Outcome::value).sum()).isPositive();
    }
  }
}
