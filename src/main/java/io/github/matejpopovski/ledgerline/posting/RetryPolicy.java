package io.github.matejpopovski.ledgerline.posting;

import java.time.Duration;
import java.util.random.RandomGenerator;

/** Bounded exponential backoff with full jitter, which spreads out colliding retriers. */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
  }

  /** The pause after the given failed attempt, counted from 1. */
  public Duration delayAfter(int failedAttempt, RandomGenerator random) {
    int doublings = Math.min(failedAttempt - 1, 20);
    long ceiling = Math.min(baseDelay.toNanos() << doublings, maxDelay.toNanos());
    return Duration.ofNanos(random.nextLong(ceiling + 1));
  }
}
