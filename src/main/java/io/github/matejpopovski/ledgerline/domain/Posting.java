package io.github.matejpopovski.ledgerline.domain;

import java.util.UUID;

/** A committed journal line. */
public record Posting(
    UUID accountId, Direction direction, long amountMinor, long balanceAfterMinor) {}
