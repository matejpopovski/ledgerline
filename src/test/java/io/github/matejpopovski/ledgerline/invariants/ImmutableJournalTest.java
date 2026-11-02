package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ALREADY_REVERSED;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ENTRY_NOT_FOUND;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Posted history never changes. The only way to correct an entry is to post its mirror image. */
class ImmutableJournalTest {

  private static void execute(TestLedger ledger, String sql) {
    ledger.jdbc.sql(sql).update();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "UPDATE journal_entries SET description = 'edited'",
        "UPDATE journal_entries SET posted_at = now() - interval '1 year'",
        "DELETE FROM journal_entries",
        "TRUNCATE journal_entries CASCADE",
        "UPDATE postings SET amount_minor = amount_minor + 1",
        "UPDATE postings SET balance_after_minor = 0",
        "DELETE FROM postings",
        "TRUNCATE postings"
      })
  void theDatabaseRefusesEveryKindOfEdit(String attack) {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      ledger.fundedWallet("USD", 100);
      long entries = ledger.count("journal_entries");
      long postings = ledger.count("postings");

      assertSqlState("LL001", () -> execute(ledger, attack));

      assertThat(ledger.count("journal_entries")).isEqualTo(entries);
      assertThat(ledger.count("postings")).isEqualTo(postings);
    }
  }

  @Test
  void anAccountCannotChangeItsCurrencyOrNormalSide() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      ledger.fundedWallet("USD", 100);

      assertSqlState("LL003", () -> execute(ledger, "UPDATE accounts SET normal_side = 'D'"));
    }
  }

  @Test
  void aReversalRestoresBalancesAndLeavesTheOriginalInPlace() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      PostedEntry original = ledger.move("pay", from, to, 40);

      PostedEntry reversal = ledger.posting.reverse(original.id(), "undo-pay");

      assertThat(ledger.balance(from)).isEqualTo(100);
      assertThat(ledger.balance(to)).isZero();
      assertThat(reversal.reversesEntryId()).isEqualTo(original.id());
      assertThat(reversal.id()).isNotEqualTo(original.id());
      assertThat(ledger.store.findEntry(original.id()).orElseThrow().entry().postings())
          .containsExactlyElementsOf(original.postings());
      assertThat(reversal.postings())
          .extracting(p -> p.direction())
          .containsExactly(Direction.CREDIT, Direction.DEBIT);
      assertAuditClean(ledger);
    }
  }

  @Test
  void aRetriedReversalIsAReplay() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      PostedEntry original = ledger.move("pay", from, to, 40);
      PostedEntry first = ledger.posting.reverse(original.id(), "undo");

      PostedEntry retried = ledger.posting.reverse(original.id(), "undo");

      assertThat(retried.replayed()).isTrue();
      assertThat(retried.id()).isEqualTo(first.id());
      assertThat(ledger.balance(to)).isZero();
    }
  }

  @Test
  void anEntryCanOnlyBeReversedOnce() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      PostedEntry original = ledger.move("pay", from, to, 40);
      ledger.posting.reverse(original.id(), "undo-1");

      assertRejected(ALREADY_REVERSED, () -> ledger.posting.reverse(original.id(), "undo-2"));
      assertThat(ledger.balance(from)).isEqualTo(100);
    }
  }

  @StrategiesTest
  void racingReversalsWithDifferentKeysApplyOnce(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      PostedEntry original = ledger.move("pay", from, to, 40);

      List<Outcome<PostedEntry>> outcomes =
          Race.run(8, i -> ledger.posting.reverse(original.id(), "undo-" + i));

      assertThat(outcomes.stream().filter(Outcome::succeeded)).hasSize(1);
      assertThat(outcomes.stream().filter(o -> !o.succeeded()))
          .allSatisfy(
              refused ->
                  assertThat(refused.failure())
                      .isInstanceOfSatisfying(
                          LedgerRejection.class,
                          r -> assertThat(r.reason()).isEqualTo(ALREADY_REVERSED)));
      assertThat(ledger.balance(from)).isEqualTo(100);
      assertAuditClean(ledger);
    }
  }

  @Test
  void reversingMoneyThatWasAlreadySpentBounces() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account treasury = ledger.treasury("USD");
      Account wallet = ledger.wallet("USD");
      Account shop = ledger.wallet("USD");
      PostedEntry deposit = ledger.deposit(treasury, wallet, 100);
      ledger.move("spend", wallet, shop, 80);

      assertRejected(INSUFFICIENT_FUNDS, () -> ledger.posting.reverse(deposit.id(), "claw-back"));
    }
  }

  @Test
  void reversingAnUnknownEntryIsNotFound() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      assertRejected(ENTRY_NOT_FOUND, () -> ledger.posting.reverse(UUID.randomUUID(), "k"));
    }
  }
}
