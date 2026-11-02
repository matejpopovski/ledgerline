package io.github.matejpopovski.ledgerline.domain;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.BALANCE_OUT_OF_RANGE;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INSUFFICIENT_FUNDS;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private static final UUID ID = UUID.randomUUID();

  private static Account account(Direction normalSide, boolean overdraft, long balance) {
    return new Account(ID, "a", "a", "USD", normalSide, overdraft, balance, Instant.EPOCH);
  }

  @Test
  void theNormalSideRaisesTheBalanceAndTheOtherSideLowersIt() {
    Account wallet = account(Direction.CREDIT, false, 100);
    Account cash = account(Direction.DEBIT, false, 100);

    assertThat(wallet.balanceAfter(new Leg(ID, Direction.CREDIT, 30))).isEqualTo(130);
    assertThat(wallet.balanceAfter(new Leg(ID, Direction.DEBIT, 30))).isEqualTo(70);
    assertThat(cash.balanceAfter(new Leg(ID, Direction.DEBIT, 30))).isEqualTo(130);
    assertThat(cash.balanceAfter(new Leg(ID, Direction.CREDIT, 30))).isEqualTo(70);
  }

  @Test
  void aBalanceMayReachExactlyZero() {
    assertThat(account(Direction.CREDIT, false, 30).balanceAfter(new Leg(ID, Direction.DEBIT, 30)))
        .isZero();
  }

  @Test
  void oneMinorUnitPastZeroIsRefused() {
    Account wallet = account(Direction.CREDIT, false, 30);

    assertRejected(INSUFFICIENT_FUNDS, () -> wallet.balanceAfter(new Leg(ID, Direction.DEBIT, 31)));
  }

  @Test
  void overdraftAccountsMayGoNegative() {
    assertThat(account(Direction.DEBIT, true, 0).balanceAfter(new Leg(ID, Direction.CREDIT, 30)))
        .isEqualTo(-30);
  }

  @Test
  void balancesStayInsideTheJsonSafeRange() {
    Account full = account(Direction.CREDIT, false, MinorUnits.MAX);

    assertRejected(BALANCE_OUT_OF_RANGE, () -> full.balanceAfter(new Leg(ID, Direction.CREDIT, 1)));
  }
}
