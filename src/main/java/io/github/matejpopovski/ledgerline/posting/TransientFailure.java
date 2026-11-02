package io.github.matejpopovski.ledgerline.posting;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

/** Recognises database failures that mean "nothing happened, try the transaction again". */
final class TransientFailure {

  private static final Set<String> RETRYABLE =
      Set.of(
          "40001", // serialization_failure
          "40P01", // deadlock_detected
          "55P03"); // lock_not_available, from lock_timeout

  private TransientFailure() {}

  /**
   * The SQLSTATE if this failure is retryable. Reading it directly keeps the decision independent
   * of how Spring translated the exception, which differs between a statement and a COMMIT.
   */
  static Optional<String> retryableState(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sql) {
        for (SQLException e = sql; e != null; e = e.getNextException()) {
          String state = e.getSQLState();
          if (state != null && RETRYABLE.contains(state)) {
            return Optional.of(state);
          }
        }
      }
    }
    return Optional.empty();
  }
}
