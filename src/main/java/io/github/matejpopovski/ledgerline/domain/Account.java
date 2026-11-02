package io.github.matejpopovski.ledgerline.domain;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.BALANCE_OUT_OF_RANGE;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INSUFFICIENT_FUNDS;

import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account. The balance is stored on the account's normal side: a customer wallet is a
 * credit-normal liability, so a credit raises its balance, while cash is debit-normal.
 */
public record Account(
    UUID id,
    String code,
    String name,
    String currency,
    Direction normalSide,
    boolean overdraftAllowed,
    long balanceMinor,
    Instant createdAt) {

  /** The balance after applying a leg, or a rejection if the account may not end up there. */
  public long balanceAfter(Leg leg) {
    long delta = leg.direction() == normalSide ? leg.amountMinor() : -leg.amountMinor();
    long next = Math.addExact(balanceMinor, delta);
    if (Math.abs(next) > MinorUnits.MAX) {
      throw new LedgerRejection(BALANCE_OUT_OF_RANGE, "balance would leave the supported range");
    }
    if (!overdraftAllowed && next < 0) {
      throw new LedgerRejection(
          INSUFFICIENT_FUNDS,
          "account %s holds %d, posting needs %d".formatted(code, balanceMinor, leg.amountMinor()));
    }
    return next;
  }
}
