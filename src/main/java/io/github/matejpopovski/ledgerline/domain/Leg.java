package io.github.matejpopovski.ledgerline.domain;

import java.util.UUID;

/** One line of a journal entry, before it is posted. */
public record Leg(UUID accountId, Direction direction, long amountMinor) {

  public long signedAmount() {
    return direction == Direction.DEBIT ? amountMinor : -amountMinor;
  }
}
