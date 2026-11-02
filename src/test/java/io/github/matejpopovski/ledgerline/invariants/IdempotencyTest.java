package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.IDEMPOTENCY_KEY_REUSED;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INSUFFICIENT_FUNDS;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertAuditClean;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.Race;
import io.github.matejpopovski.ledgerline.support.Race.Outcome;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** One key moves money at most once, however many times and however concurrently it arrives. */
class IdempotencyTest {

  @StrategiesTest
  void aRetriedRequestPostsOnceAndReturnsTheOriginalEntry(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account from = ledger.fundedWallet("USD", 1_000);
      Account to = ledger.wallet("USD");

      PostedEntry first = ledger.move("pay-1", from, to, 300);
      PostedEntry retried = ledger.move("pay-1", from, to, 300);

      assertThat(first.replayed()).isFalse();
      assertThat(retried.replayed()).isTrue();
      assertThat(retried.id()).isEqualTo(first.id());
      assertThat(retried.sequence()).isEqualTo(first.sequence());
      assertThat(retried.postings()).containsExactlyElementsOf(first.postings());
      assertThat(ledger.balance(from)).isEqualTo(700);
      assertThat(ledger.balance(to)).isEqualTo(300);
    }
  }

  @StrategiesTest
  void aRetryIsStillAnsweredAfterTheFundsAreGone(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      PostedEntry first = ledger.move("spend-all", from, to, 100);

      PostedEntry retried = ledger.move("spend-all", from, to, 100);

      assertThat(retried.id()).isEqualTo(first.id());
      assertThat(ledger.balance(from)).isZero();
    }
  }

  @StrategiesTest
  void concurrentDuplicatesPostOnceAndAllSeeTheSameEntry(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account from = ledger.fundedWallet("USD", 1_000_000);
      Account to = ledger.wallet("USD");
      long entriesBefore = ledger.count("journal_entries");

      for (int round = 0; round < 20; round++) {
        String key = "dup-" + round;
        List<Outcome<PostedEntry>> outcomes = Race.run(8, i -> ledger.move(key, from, to, 10));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(outcomes.stream().map(o -> o.value().id()).distinct()).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.value().replayed())).hasSize(1);
      }

      assertThat(ledger.count("journal_entries") - entriesBefore).isEqualTo(20);
      assertThat(ledger.balance(to)).isEqualTo(200);
      assertAuditClean(ledger);
    }
  }

  /**
   * The duplicate must be recognised before the funds check. If the funds check ran first, the
   * loser would read the winner's debit and answer "insufficient funds" to a client whose payment
   * in fact went through.
   */
  @StrategiesTest
  void concurrentDuplicatesOfARequestThatEmptiesTheAccountAllSucceed(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account to = ledger.wallet("USD");

      for (int round = 0; round < 20; round++) {
        Account from = ledger.fundedWallet("USD", 100);
        String key = "drain-" + round;

        List<Outcome<PostedEntry>> outcomes = Race.run(8, i -> ledger.move(key, from, to, 100));

        assertThat(outcomes).allMatch(Outcome::succeeded);
        assertThat(ledger.balance(from)).isZero();
      }
      assertThat(ledger.balance(to)).isEqualTo(2_000);
    }
  }

  @Test
  void aKeyReusedForADifferentRequestIsRefused() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 1_000);
      Account to = ledger.wallet("USD");
      ledger.move("shared", from, to, 300);

      assertRejected(IDEMPOTENCY_KEY_REUSED, () -> ledger.move("shared", from, to, 301));
      assertThat(ledger.balance(from)).isEqualTo(700);
    }
  }

  @Test
  void aRefusedRequestDoesNotUseUpItsKey() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account treasury = ledger.treasury("USD");
      Account from = ledger.wallet("USD");
      Account to = ledger.wallet("USD");
      assertRejected(INSUFFICIENT_FUNDS, () -> ledger.move("too-soon", from, to, 50));

      ledger.deposit(treasury, from, 50);
      PostedEntry retried = ledger.move("too-soon", from, to, 50);

      assertThat(retried.replayed()).isFalse();
      assertThat(ledger.balance(to)).isEqualTo(50);
    }
  }

  @StrategiesTest
  void aWaitingDuplicateTakesOverWhenTheFirstRequestIsRefused(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account from = ledger.fundedWallet("USD", 10);
      Account to = ledger.wallet("USD");

      List<Outcome<PostedEntry>> outcomes =
          Race.run(8, i -> ledger.move("all-refused", from, to, 11));

      assertThat(outcomes).noneMatch(Outcome::succeeded);
      assertThat(ledger.balance(from)).isEqualTo(10);
      assertThat(
              ledger
                  .jdbc
                  .sql("SELECT count(*) FROM journal_entries WHERE idempotency_key = 'all-refused'")
                  .query(Long.class)
                  .single())
          .isZero();
    }
  }

  @Test
  void aKeyIsGlobalNotPerAccountPair() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account a = ledger.fundedWallet("USD", 100);
      Account b = ledger.wallet("USD");
      Account c = ledger.wallet("USD");
      EntryDraft toB = ledger.draft(UUID.randomUUID().toString(), a, b, 10);
      ledger.posting.post(toB);

      assertRejected(IDEMPOTENCY_KEY_REUSED, () -> ledger.move(toB.idempotencyKey(), a, c, 10));
    }
  }
}
