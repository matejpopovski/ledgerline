package io.github.matejpopovski.ledgerline.domain;

import java.time.Instant;
import java.util.UUID;

/** One row of an account statement. {@code cursor} pages backwards through the history. */
public record StatementLine(
    long cursor,
    UUID entryId,
    Instant postedAt,
    String description,
    Direction direction,
    long amountMinor,
    long balanceAfterMinor) {}
