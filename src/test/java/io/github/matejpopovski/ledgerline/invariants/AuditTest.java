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
import java.util.concurrent.atomic.AtomicBoolean;
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

  /** Commits landing between the audit's queries must not read as drift: it needs one snapshot. */
  @Test
  void aRunningAuditNeverFalselyAlarmsDuringConcurrentPosting() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account a = ledger.fundedWallet("USD", 1_000_000);
      Account b = ledger.wallet("USD");
      AtomicBoolean done = new AtomicBoolean();

      List<Outcome<Integer>> outcomes =
          Race.run(
              5,
              racer -> {
                int cleanRuns = 0;
                if (racer == 0) {
                  for (int i = 0; i < 400; i++) {
                    ledger.move(UUID.randomUUID().toString(), a, b, 1);
                  }
                  done.set(true);
                  return 0;
                }
                while (!done.get()) {
                  assertThat(ledger.auditReport().clean()).isTrue();
                  cleanRuns++;
                }
                return cleanRuns;
              });

      assertThat(outcomes).allMatch(Outcome::succeeded);
      assertThat(outcomes.stream().mapToInt(Outcome::value).sum()).isPositive();
    }
  }
}
