package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INSUFFICIENT_FUNDS;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertAuditClean;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertRejected;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertSqlState;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.Race;
import io.github.matejpopovski.ledgerline.support.Race.Outcome;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An account that may not go negative does not, even when every withdrawal races. */
class OverdraftTest {

  @StrategiesTest
  void aWithdrawalBeyondTheBalanceIsRefusedAndWritesNothing(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      long entriesBefore = ledger.count("journal_entries");

      assertRejected(INSUFFICIENT_FUNDS, () -> ledger.move("over", from, to, 101));

      assertThat(ledger.balance(from)).isEqualTo(100);
      assertThat(ledger.count("journal_entries")).isEqualTo(entriesBefore);
    }
  }

  @StrategiesTest
  void racingWithdrawalsCannotSpendTheSameMoneyTwice(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      for (int round = 0; round < 5; round++) {
        Account from = ledger.fundedWallet("USD", 100);
        Account to = ledger.wallet("USD");

        List<Outcome<PostedEntry>> outcomes =
            Race.run(24, i -> ledger.move(UUID.randomUUID().toString(), from, to, 10));

        long accepted = outcomes.stream().filter(Outcome::succeeded).count();
        assertThat(accepted).as("withdrawals of 10 from 100").isEqualTo(10);
        assertThat(outcomes.stream().filter(o -> !o.succeeded()))
            .allSatisfy(
                refused ->
                    assertThat(refused.failure())
                        .isInstanceOfSatisfying(
                            LedgerRejection.class,
                            r -> assertThat(r.reason()).isEqualTo(INSUFFICIENT_FUNDS)));
        assertThat(ledger.balance(from)).isZero();
        assertThat(ledger.balance(to)).isEqualTo(100);
      }
      assertAuditClean(ledger);
    }
  }

  @Test
  void theDatabaseRefusesANegativeBalanceWrittenBehindTheService() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);

      assertSqlState(
          "23514",
          () ->
              ledger
                  .jdbc
                  .sql("UPDATE accounts SET balance_minor = -1 WHERE id = :id")
                  .param("id", wallet.id())
                  .update());
    }
  }

  @Test
  void anAccountWithOverdraftAllowedMayGoNegative() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account creditLine = ledger.accounts.open("line", "line", "USD", Direction.CREDIT, true);
      Account wallet = ledger.wallet("USD");

      ledger.move("draw", creditLine, wallet, 200);

      assertThat(ledger.balance(creditLine)).isEqualTo(-200);
      assertAuditClean(ledger);
    }
  }
}
