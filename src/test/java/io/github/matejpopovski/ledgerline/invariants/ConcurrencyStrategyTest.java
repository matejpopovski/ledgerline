package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.CONTENTION_LIMIT;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertAuditClean;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.posting.RetryPolicy;
import io.github.matejpopovski.ledgerline.posting.RetryingTransactions;
import io.github.matejpopovski.ledgerline.support.Race;
import io.github.matejpopovski.ledgerline.support.Race.Outcome;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** The behaviour specific to each way of keeping concurrent writers from losing updates. */
class ConcurrencyStrategyTest {

  private static final int RACERS = 16;
  private static final int PER_RACER = 40;

  @StrategiesTest
  void noUpdateIsLostOnAHotAccount(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account hot = ledger.wallet("USD");
      Account treasury = ledger.treasury("USD");

      List<Outcome<Object>> outcomes =
          Race.run(
              RACERS,
              racer -> {
                for (int i = 0; i < PER_RACER; i++) {
                  ledger.deposit(treasury, hot, 1);
                }
                return null;
              });

      assertThat(outcomes).allMatch(Outcome::succeeded);
      assertThat(ledger.balance(hot)).isEqualTo(RACERS * PER_RACER);
      assertThat(ledger.count("postings")).isEqualTo(2L * RACERS * PER_RACER);
      assertAuditClean(ledger);
    }
  }

  /** Without a canonical lock order, A-to-B and B-to-A are the textbook deadlock. */
  @StrategiesTest
  void opposingTransfersDoNotDeadlock(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      Account a = ledger.fundedWallet("USD", 100_000);
      Account b = ledger.fundedWallet("USD", 100_000);

      List<Outcome<Object>> outcomes =
          Race.run(
              RACERS,
              racer -> {
                for (int i = 0; i < 25; i++) {
                  Account from = racer % 2 == 0 ? a : b;
                  Account to = racer % 2 == 0 ? b : a;
                  ledger.move(UUID.randomUUID().toString(), from, to, 1);
                }
                return null;
              });

      assertThat(outcomes).allMatch(Outcome::succeeded);
      assertThat(ledger.retries("40P01")).as("deadlocks broken by Postgres").isZero();
      assertThat(ledger.balance(a) + ledger.balance(b)).isEqualTo(200_000);
      assertAuditClean(ledger);
    }
  }

  @Test
  void rowLocksQueueWritersSoNothingIsRetried() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account hot = ledger.wallet("USD");
      Account treasury = ledger.treasury("USD");

      Race.run(RACERS, racer -> ledger.deposit(treasury, hot, 1));

      assertThat(ledger.retries("40001")).isZero();
      assertThat(ledger.balance(hot)).isEqualTo(RACERS);
    }
  }

  /** Guards against a vacuous pass: the contention above must really produce conflicts. */
  @Test
  void serializableConflictsAreRealAndRetriedAway() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.SERIALIZABLE)) {
      Account hot = ledger.wallet("USD");
      Account treasury = ledger.treasury("USD");

      List<Outcome<Object>> outcomes =
          Race.run(
              RACERS,
              racer -> {
                for (int i = 0; i < 10; i++) {
                  ledger.deposit(treasury, hot, 1);
                }
                return null;
              });

      assertThat(outcomes).allMatch(Outcome::succeeded);
      assertThat(ledger.retries("40001")).isPositive();
      assertThat(ledger.balance(hot)).isEqualTo(RACERS * 10);
    }
  }

  @Test
  void anExhaustedRetryBudgetBecomesAContentionRefusalAndWritesNothing() {
    RetryPolicy noRetries = new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1));
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.SERIALIZABLE, noRetries)) {
      Account hot = ledger.wallet("USD");
      Account treasury = ledger.treasury("USD");

      List<Outcome<PostedEntry>> outcomes =
          Race.run(RACERS, racer -> ledger.deposit(treasury, hot, 1));

      long accepted = outcomes.stream().filter(Outcome::succeeded).count();
      assertThat(outcomes.stream().filter(o -> !o.succeeded()))
          .isNotEmpty()
          .allSatisfy(
              refused ->
                  assertThat(refused.failure())
                      .isInstanceOfSatisfying(
                          LedgerRejection.class,
                          r -> assertThat(r.reason()).isEqualTo(CONTENTION_LIMIT)));
      assertThat(ledger.balance(hot)).isEqualTo(accepted);
      assertAuditClean(ledger);
    }
  }

  /** Instances on different strategies overlap during a rolling restart and share the rows. */
  @Test
  void mixedStrategiesDuringARollingChangeKeepTheBooks() {
    try (TestLedger locking = TestLedger.create(ConcurrencyStrategy.ROW_LOCK);
        TestLedger serializable = locking.secondInstance(ConcurrencyStrategy.SERIALIZABLE);
        TestLedger snapshot = locking.secondInstance(ConcurrencyStrategy.REPEATABLE_READ)) {
      Account hot = locking.fundedWallet("USD", 1_200);
      Account other = locking.wallet("USD");
      List<TestLedger> instances = List.of(locking, serializable, snapshot);

      List<Outcome<PostedEntry>> outcomes =
          Race.run(
              RACERS * 2,
              racer ->
                  instances
                      .get(racer % instances.size())
                      .move(UUID.randomUUID().toString(), hot, other, 100));

      assertThat(outcomes.stream().filter(Outcome::succeeded).count()).isEqualTo(12);
      assertThat(locking.balance(hot)).isZero();
      assertAuditClean(locking);
    }
  }

  /** A transaction stuck on a row must end in a refusal the client can retry, not in a hang. */
  @Test
  void aStuckLockHolderBecomesAContentionRefusal() {
    RetryPolicy threeTries = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5));
    try (TestLedger ledger =
        TestLedger.create(ConcurrencyStrategy.ROW_LOCK, threeTries, "SET lock_timeout = '50ms'")) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");

      new TransactionTemplate(ledger.transactions)
          .executeWithoutResult(
              status -> {
                ledger.store.lockAccounts(List.of(from.id()));
                List<Outcome<PostedEntry>> blocked =
                    Race.run(1, i -> ledger.move("blocked", from, to, 10));

                assertThat(blocked.getFirst().failure())
                    .isInstanceOfSatisfying(
                        LedgerRejection.class,
                        r -> assertThat(r.reason()).isEqualTo(CONTENTION_LIMIT));
              });

      assertThat(ledger.retries("55P03")).isEqualTo(3);
      assertThat(ledger.balance(from)).isEqualTo(100);
    }
  }

  /**
   * Serialization failures can arrive at COMMIT, after every statement has succeeded. Two
   * transactions each read the row the other writes: no write conflicts, so only SSI's commit check
   * stops the skew, and the loser has to be re-run from the top.
   */
  @Test
  void aFailureAtCommitIsRetriedLikeAnyOther() throws Exception {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.SERIALIZABLE)) {
      Account x = ledger.wallet("USD");
      Account y = ledger.wallet("USD");
      RetryingTransactions serializable =
          new RetryingTransactions(
              ledger.transactions,
              ConcurrencyStrategy.SERIALIZABLE,
              TestLedger.PATIENT_RETRIES,
              ledger.meters);
      CountDownLatch bothHaveRead = new CountDownLatch(2);
      CountDownLatch winnerCommitted = new CountDownLatch(1);
      AtomicInteger loserAttempts = new AtomicInteger();

      Thread winner =
          Thread.ofPlatform()
              .start(
                  () -> {
                    serializable.execute(
                        () -> {
                          readOneWriteOther(ledger, x, y, bothHaveRead);
                          return null;
                        });
                    winnerCommitted.countDown();
                  });
      serializable.execute(
          () -> {
            if (loserAttempts.incrementAndGet() == 1) {
              readOneWriteOther(ledger, y, x, bothHaveRead);
              await(winnerCommitted);
            } else {
              readOneWriteOther(ledger, y, x, new CountDownLatch(0));
            }
            return null;
          });
      winner.join();

      assertThat(loserAttempts).hasValue(2);
      assertThat(ledger.retries("40001")).isEqualTo(1);
    }
  }

  private static void readOneWriteOther(
      TestLedger ledger, Account read, Account write, CountDownLatch rendezvous) {
    ledger
        .jdbc
        .sql("SELECT balance_minor FROM accounts WHERE id = :id")
        .param("id", read.id())
        .query(Long.class)
        .single();
    rendezvous.countDown();
    await(rendezvous);
    ledger
        .jdbc
        .sql("UPDATE accounts SET name = name WHERE id = :id")
        .param("id", write.id())
        .update();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
