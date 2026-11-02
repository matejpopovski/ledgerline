package io.github.matejpopovski.ledgerline;

import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code ledger.*}; defaults live in {@code application.yaml}. */
@ConfigurationProperties("ledger")
public record LedgerProperties(ConcurrencyStrategy strategy, Retry retry, Outbox outbox) {

  /** Backoff for transient database failures. */
  public record Retry(int maxAttempts, Duration baseDelay, Duration maxDelay) {}

  /** Outbox relay settings. A blank webhook URL leaves the relay off. */
  public record Outbox(
      String webhookUrl,
      Duration pollInterval,
      int batchSize,
      Duration lease,
      Duration requestTimeout) {}
}
