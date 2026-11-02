package io.github.matejpopovski.ledgerline.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.core.NestedExceptionUtils;

/** Assertions shared by the invariant tests. */
public final class LedgerAssertions {

  private LedgerAssertions() {}

  public static void assertRejected(LedgerRejection.Reason reason, ThrowingCallable call) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            LedgerRejection.class, rejection -> assertThat(rejection.reason()).isEqualTo(reason));
  }

  /** Asserts the database itself refused, with this SQLSTATE. */
  public static void assertSqlState(String sqlState, ThrowingCallable call) {
    assertThatThrownBy(call)
        .satisfies(
            failure -> {
              Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
              assertThat(root).isInstanceOf(SQLException.class);
              assertThat(((SQLException) root).getSQLState()).isEqualTo(sqlState);
            });
  }

  public static void assertAuditClean(TestLedger ledger) {
    var report = ledger.auditReport();
    assertThat(report.driftedAccounts()).as("accounts drifted from journal").isEmpty();
    assertThat(report.runningBalanceBreaks()).as("running balance breaks").isZero();
    assertThat(report.unbalancedEntries()).as("unbalanced entries").isZero();
    assertThat(report.overdrawnAccounts()).as("overdrawn accounts").isZero();
    assertThat(report.clean()).isTrue();
  }
}
