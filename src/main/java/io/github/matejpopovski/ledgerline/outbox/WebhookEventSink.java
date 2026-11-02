package io.github.matejpopovski.ledgerline.outbox;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/** Delivers events as HTTP POSTs. Receivers deduplicate on {@code Ledger-Event-Id}. */
public class WebhookEventSink implements EventSink {

  private final HttpClient client;
  private final URI target;
  private final Duration timeout;

  public WebhookEventSink(URI target, Duration timeout) {
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    this.target = target;
    this.timeout = timeout;
  }

  @Override
  public void deliver(UUID eventId, String type, String payload)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(target)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Ledger-Event-Id", eventId.toString())
            .header("Ledger-Event-Type", type)
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    if (status < 200 || status >= 300) {
      throw new IOException("webhook answered " + status);
    }
  }
}
