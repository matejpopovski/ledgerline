package io.github.matejpopovski.ledgerline.posting;

import org.springframework.transaction.TransactionDefinition;

/**
 * How concurrent postings to the same account are kept from losing updates. All three pass the same
 * invariant tests; the difference is where contention is paid for. Row locks queue writers, the
 * other two let them race and retry the loser.
 */
public enum ConcurrencyStrategy {
  /** READ COMMITTED plus an explicit lock on each account row, taken in id order. */
  ROW_LOCK(TransactionDefinition.ISOLATION_READ_COMMITTED, true),

  /** SERIALIZABLE (SSI) with no explicit locks, retrying on SQLSTATE 40001. */
  SERIALIZABLE(TransactionDefinition.ISOLATION_SERIALIZABLE, false),

  /**
   * REPEATABLE READ (snapshot isolation) with retry. Sufficient here because every account an entry
   * reads it also writes, so all conflicts are write-write and there is no room for write skew.
   */
  REPEATABLE_READ(TransactionDefinition.ISOLATION_REPEATABLE_READ, false);

  private final int isolationLevel;
  private final boolean locksRows;

  ConcurrencyStrategy(int isolationLevel, boolean locksRows) {
    this.isolationLevel = isolationLevel;
    this.locksRows = locksRows;
  }

  public int isolationLevel() {
    return isolationLevel;
  }

  public boolean locksRows() {
    return locksRows;
  }
}
