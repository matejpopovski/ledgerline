package io.github.matejpopovski.ledgerline.outbox;

import java.util.UUID;

/** Where outbox events go. Throwing means "not delivered" and the relay will try again. */
@FunctionalInterface
public interface EventSink {
  void deliver(UUID eventId, String type, String payload) throws Exception;
}
