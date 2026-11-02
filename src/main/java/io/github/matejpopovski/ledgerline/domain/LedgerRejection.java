package io.github.matejpopovski.ledgerline.domain;

/**
 * A request the ledger refuses. Thrown inside the posting transaction so that it also rolls back
 * the idempotency claim: a refused request leaves no trace and its key stays usable.
 */
public final class LedgerRejection extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why a request was refused; the API maps each reason to a status and a stable code. */
  public enum Reason {
    INVALID_ENTRY,
    UNBALANCED_ENTRY,
    UNKNOWN_CURRENCY,
    ACCOUNT_NOT_FOUND,
    ENTRY_NOT_FOUND,
    ACCOUNT_CODE_TAKEN,
    CURRENCY_MISMATCH,
    INSUFFICIENT_FUNDS,
    BALANCE_OUT_OF_RANGE,
    IDEMPOTENCY_KEY_REUSED,
    ALREADY_REVERSED,
    CONTENTION_LIMIT
  }

  private final Reason reason;

  public LedgerRejection(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
