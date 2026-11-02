package io.github.matejpopovski.ledgerline.outbox;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INSUFFICIENT_FUNDS;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.outbox.OutboxStore.OutboxEvent;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.Race;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An event exists if and only if its entry committed, and gets delivered at least once. */
class OutboxTest {

  private static final Duration LEASE = Duration.ofSeconds(30);

  private static OutboxRelay relay(TestLedger ledger, EventSink sink) {
    return new OutboxRelay(ledger.outbox, sink, 100, LEASE, ledger.meters);
  }

  private static long pending(TestLedger ledger) {
    return ledger
        .jdbc
        .sql("SELECT count(*) FROM outbox_events WHERE published_at IS NULL")
        .query(Long.class)
        .single();
  }

  private static void expireLeases(TestLedger ledger) {
    ledger.jdbc.sql("UPDATE outbox_events SET next_attempt_at = now()").update();
  }

  @Test
  void aCommittedEntryLeavesExactlyOneEventDescribingIt() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      long before = ledger.count("outbox_events");

      PostedEntry entry = ledger.move("pay", from, to, 30);

      assertThat(ledger.count("outbox_events")).isEqualTo(before + 1);
      String payload =
          ledger
              .jdbc
              .sql("SELECT payload::text FROM outbox_events WHERE entry_id = :id")
              .param("id", entry.id())
              .query(String.class)
              .single();
      JsonNode json = JsonMapper.builder().build().readTree(payload);
      assertThat(json.get("type").asString()).isEqualTo(OutboxStore.ENTRY_POSTED);
      assertThat(json.get("entry").get("id").asString()).isEqualTo(entry.id().toString());
      assertThat(json.get("entry").get("postings").size()).isEqualTo(2);
    }
  }

  @Test
  void aRefusedEntryLeavesNoEvent() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      long before = ledger.count("outbox_events");

      assertRejected(INSUFFICIENT_FUNDS, () -> ledger.move("over", from, to, 101));

      assertThat(ledger.count("outbox_events")).isEqualTo(before);
    }
  }

  /** The dual-write bug this pattern exists to prevent: money moved, and nobody was told. */
  @Test
  void anEntryThatCannotRecordItsEventDoesNotPost() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      long entriesBefore = ledger.count("journal_entries");
      ledger
          .jdbc
          .sql("ALTER TABLE outbox_events ADD CONSTRAINT refuse_all CHECK (false) NOT VALID")
          .update();

      assertThatThrownBy(() -> ledger.move("pay", from, to, 30))
          .isInstanceOf(DataIntegrityViolationException.class);

      assertThat(ledger.balance(from)).isEqualTo(100);
      assertThat(ledger.count("journal_entries")).isEqualTo(entriesBefore);
    }
  }

  @Test
  void aReplayLeavesNoSecondEvent() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account from = ledger.fundedWallet("USD", 100);
      Account to = ledger.wallet("USD");
      ledger.move("pay", from, to, 30);
      long afterFirst = ledger.count("outbox_events");

      ledger.move("pay", from, to, 30);

      assertThat(ledger.count("outbox_events")).isEqualTo(afterFirst);
    }
  }

  @Test
  void theRelayDeliversEverythingOnceWhenNothingFails() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);
      Account other = ledger.wallet("USD");
      ledger.move("pay", wallet, other, 1);
      List<UUID> delivered = new ArrayList<>();
      OutboxRelay relay = relay(ledger, (id, type, payload) -> delivered.add(id));

      assertThat(relay.drainOnce()).isEqualTo(2);
      assertThat(relay.drainOnce()).isZero();

      assertThat(delivered).doesNotHaveDuplicates().hasSize(2);
      assertThat(pending(ledger)).isZero();
    }
  }

  @Test
  void aFailedDeliveryStaysQueuedBacksOffAndIsRetried() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      ledger.fundedWallet("USD", 100);
      AtomicInteger calls = new AtomicInteger();
      OutboxRelay relay =
          relay(
              ledger,
              (id, type, payload) -> {
                if (calls.incrementAndGet() == 1) {
                  throw new IOException("receiver is down");
                }
              });

      assertThat(relay.drainOnce()).isZero();

      assertThat(pending(ledger)).isEqualTo(1);
      assertThat(relay.drainOnce()).as("not due yet").isZero();
      assertThat(
              ledger.jdbc.sql("SELECT last_error FROM outbox_events").query(String.class).single())
          .contains("receiver is down");

      expireLeases(ledger);
      assertThat(relay.drainOnce()).isEqualTo(1);
      assertThat(pending(ledger)).isZero();
      assertThat(
              ledger.jdbc.sql("SELECT attempts FROM outbox_events").query(Integer.class).single())
          .isEqualTo(2);
    }
  }

  @Test
  void backoffGrowsAndIsCapped() {
    assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(OutboxRelay.backoff(5)).isEqualTo(Duration.ofSeconds(32));
    assertThat(OutboxRelay.backoff(40)).isEqualTo(Duration.ofMinutes(5));
  }

  /** The lease is what turns a crash into a redelivery instead of a lost event. */
  @Test
  void anEventClaimedByACrashedRelayIsRedeliveredAfterItsLease() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      ledger.fundedWallet("USD", 100);
      List<OutboxEvent> claimedByTheDeadRelay = ledger.outbox.claimDue(100, LEASE);
      assertThat(claimedByTheDeadRelay).hasSize(1);
      List<UUID> delivered = new ArrayList<>();
      OutboxRelay survivor = relay(ledger, (id, type, payload) -> delivered.add(id));

      assertThat(survivor.drainOnce()).as("leased elsewhere").isZero();
      expireLeases(ledger);
      assertThat(survivor.drainOnce()).isEqualTo(1);

      assertThat(delivered).containsExactly(claimedByTheDeadRelay.getFirst().eventId());
    }
  }

  @Test
  void parallelRelaysNeverClaimTheSameEvent() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 1_000);
      Account other = ledger.wallet("USD");
      for (int i = 0; i < 200; i++) {
        ledger.move("m" + i, wallet, other, 1);
      }
      ConcurrentLinkedQueue<UUID> delivered = new ConcurrentLinkedQueue<>();
      OutboxRelay relay =
          new OutboxRelay(
              ledger.outbox, (id, type, payload) -> delivered.add(id), 7, LEASE, ledger.meters);

      Race.run(
          6,
          racer -> {
            int batch;
            do {
              batch = relay.drainOnce();
            } while (batch > 0);
            return null;
          });
      relay.drainOnce();

      assertThat(delivered).doesNotHaveDuplicates().hasSize(201);
      assertThat(pending(ledger)).isZero();
    }
  }

  @Test
  void theWebhookSinkPostsTheEventAndTreatsNon2xxAsFailure() throws Exception {
    ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
    AtomicInteger status = new AtomicInteger(204);
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/hook",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          received.add(exchange.getRequestHeaders().getFirst("Ledger-Event-Id") + " " + body);
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    try {
      URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
      WebhookEventSink sink = new WebhookEventSink(uri, Duration.ofSeconds(2));
      UUID id = UUID.randomUUID();

      sink.deliver(id, "t", "{\"a\":1}");

      assertThat(received).containsExactly(id + " {\"a\":1}");
      status.set(503);
      assertThatThrownBy(() -> sink.deliver(id, "t", "{}"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("503");
    } finally {
      server.stop(0);
    }
  }
}
