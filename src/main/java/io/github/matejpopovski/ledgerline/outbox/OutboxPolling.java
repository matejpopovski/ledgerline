package io.github.matejpopovski.ledgerline.outbox;

import io.github.matejpopovski.ledgerline.LedgerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Polls the outbox on a schedule. Only exists when a webhook URL is configured. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression("!'${ledger.outbox.webhook-url:}'.isBlank()")
class OutboxPolling {

  @Bean
  OutboxRelay outboxRelay(OutboxStore store, LedgerProperties properties, MeterRegistry meters) {
    LedgerProperties.Outbox outbox = properties.outbox();
    return new OutboxRelay(
        store,
        new WebhookEventSink(URI.create(outbox.webhookUrl()), outbox.requestTimeout()),
        outbox.batchSize(),
        outbox.lease(),
        meters);
  }

  @Bean
  Poller outboxPoller(OutboxRelay relay, LedgerProperties properties) {
    return new Poller(relay, properties.outbox().batchSize());
  }

  /** Holds the schedule, so the relay itself stays free of Spring and tests can drive it. */
  static class Poller {
    private final OutboxRelay relay;
    private final int batchSize;

    Poller(OutboxRelay relay, int batchSize) {
      this.relay = relay;
      this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.poll-interval}")
    void poll() {
      int delivered;
      do {
        delivered = relay.drainOnce();
      } while (delivered == batchSize);
    }
  }
}
