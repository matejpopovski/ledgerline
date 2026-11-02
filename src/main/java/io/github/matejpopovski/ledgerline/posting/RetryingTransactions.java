package io.github.matejpopovski.ledgerline.posting;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.CONTENTION_LIMIT;

import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction at the strategy's isolation level and re-runs it from scratch on a
 * transient failure. The whole transaction is the unit of retry: a failure can surface at COMMIT,
 * after every statement has succeeded, and the work must read fresh state each time.
 */
public class RetryingTransactions {

  private final TransactionTemplate transactions;
  private final RetryPolicy policy;
  private final MeterRegistry meters;

  public RetryingTransactions(
      PlatformTransactionManager transactionManager,
      ConcurrencyStrategy strategy,
      RetryPolicy policy,
      MeterRegistry meters) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setIsolationLevel(strategy.isolationLevel());
    this.policy = policy;
    this.meters = meters;
  }

  public <T> T execute(Supplier<T> work) {
    for (int attempt = 1; ; attempt++) {
      try {
        return transactions.execute(status -> work.get());
      } catch (RuntimeException failure) {
        Optional<String> state = TransientFailure.retryableState(failure);
        if (state.isEmpty()) {
          throw failure;
        }
        meters.counter("ledger.transaction.retries", "sqlstate", state.get()).increment();
        if (attempt == policy.maxAttempts()) {
          meters.counter("ledger.transaction.exhausted").increment();
          throw new LedgerRejection(
              CONTENTION_LIMIT, "gave up after " + attempt + " attempts, last " + state.get());
        }
        pause(policy.delayAfter(attempt, ThreadLocalRandom.current()));
      }
    }
  }

  private static void pause(Duration delay) {
    try {
      Thread.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while backing off", e);
    }
  }
}
