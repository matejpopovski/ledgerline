package io.github.matejpopovski.ledgerline.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionSystemException;

class TransientFailureTest {

  @ParameterizedTest
  @ValueSource(strings = {"40001", "40P01", "55P03"})
  void recognisesRetryableStatesBehindAnyWrapper(String state) {
    RuntimeException wrapped =
        new TransactionSystemException("commit failed", new SQLException("x", state));

    assertThat(TransientFailure.retryableState(wrapped)).contains(state);
  }

  @Test
  void findsTheStateChainedBehindABatchFailure() {
    BatchUpdateException batch = new BatchUpdateException();
    batch.setNextException(new SQLException("x", "40001"));

    assertThat(TransientFailure.retryableState(new IllegalStateException(batch))).contains("40001");
  }

  @Test
  void aConstraintViolationIsNotRetried() {
    RuntimeException failure =
        new DataIntegrityViolationException("no", new SQLException("x", "23514"));

    assertThat(TransientFailure.retryableState(failure)).isEmpty();
  }
}
