package io.github.matejpopovski.ledgerline.outbox;

import io.github.matejpopovski.ledgerline.outbox.OutboxStore.OutboxEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves committed events to the sink. No transaction is open while the sink is called, so a slow
 * receiver costs a lease and not a held connection.
 *
 * <p>Delivery is at least once. A crash after the sink accepts an event and before {@link
 * OutboxStore#markPublished} redelivers it when the lease expires; there is no way to make those
 * two steps atomic without the receiver taking part.
 */
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final long MAX_BACKOFF_SECONDS = 300;

  private final OutboxStore store;
  private final EventSink sink;
  private final int batchSize;
  private final Duration lease;
  private final MeterRegistry meters;

  public OutboxRelay(
      OutboxStore store, EventSink sink, int batchSize, Duration lease, MeterRegistry meters) {
    this.store = store;
    this.sink = sink;
    this.batchSize = batchSize;
    this.lease = lease;
    this.meters = meters;
  }

  /** Delivers one batch and returns how many events were accepted by the sink. */
  public int drainOnce() {
    List<OutboxEvent> batch = store.claimDue(batchSize, lease);
    int delivered = 0;
    for (OutboxEvent event : batch) {
      if (deliver(event)) {
        delivered++;
      }
    }
    return delivered;
  }

  private boolean deliver(OutboxEvent event) {
    try {
      sink.deliver(event.eventId(), event.type(), event.payload());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      recordFailure(event, e);
      return false;
    } catch (Exception e) {
      recordFailure(event, e);
      return false;
    }
    store.markPublished(event.id());
    meters.counter("ledger.outbox.delivered").increment();
    return true;
  }

  private void recordFailure(OutboxEvent event, Exception failure) {
    meters.counter("ledger.outbox.failed").increment();
    log.warn("outbox event {} attempt {} failed", event.eventId(), event.attempts(), failure);
    store.markFailed(event.id(), String.valueOf(failure), backoff(event.attempts()));
  }

  static Duration backoff(int attempts) {
    return Duration.ofSeconds(Math.min(1L << Math.min(attempts, 20), MAX_BACKOFF_SECONDS));
  }
}
