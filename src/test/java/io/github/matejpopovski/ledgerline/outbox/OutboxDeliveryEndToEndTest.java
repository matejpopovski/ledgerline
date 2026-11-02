package io.github.matejpopovski.ledgerline.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.Leg;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.AccountService;
import io.github.matejpopovski.ledgerline.posting.PostingService;
import io.github.matejpopovski.ledgerline.support.TestDatabase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The scheduled relay as the application wires it, delivering to a live HTTP receiver. */
@SpringBootTest
class OutboxDeliveryEndToEndTest {

  private static final BlockingQueue<String> RECEIVED = new LinkedBlockingQueue<>();
  private static final HttpServer RECEIVER = startReceiver();

  @Autowired private PostingService posting;
  @Autowired private AccountService accounts;

  private static HttpServer startReceiver() {
    try {
      HttpServer server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext(
          "/events",
          exchange -> {
            RECEIVED.add(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
          });
      server.start();
      return server;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @AfterAll
  static void stopReceiver() {
    RECEIVER.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", TestDatabase::freshDatabaseUrl);
    registry.add("spring.datasource.username", () -> "postgres");
    registry.add("ledger.outbox.poll-interval", () -> "50ms");
    registry.add(
        "ledger.outbox.webhook-url",
        () -> "http://localhost:" + RECEIVER.getAddress().getPort() + "/events");
  }

  @Test
  void aPostedEntryReachesTheWebhookWithoutAnyoneAsking() throws Exception {
    Account cash = accounts.open("cash", "Cash", "USD", Direction.DEBIT, true);
    Account alice = accounts.open("alice", "Alice", "USD", Direction.CREDIT, false);

    PostedEntry entry =
        posting.post(
            EntryDraft.of(
                "first",
                "USD",
                "deposit",
                List.of(
                    new Leg(cash.id(), Direction.DEBIT, 500),
                    new Leg(alice.id(), Direction.CREDIT, 500))));

    assertThat(RECEIVED.poll(10, TimeUnit.SECONDS)).contains(entry.id().toString());
  }
}
