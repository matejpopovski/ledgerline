package io.github.matejpopovski.ledgerline.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.support.TestDatabase;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Over real HTTP against the full Spring application, because this is where money gets parsed. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LedgerApiTest {

  private static final Path OPENAPI_SPEC = Path.of("docs/openapi.json");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http = HttpClient.newHttpClient();

  @Value("${local.server.port}")
  private int port;

  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", TestDatabase::freshDatabaseUrl);
    registry.add("spring.datasource.username", () -> "postgres");
  }

  private record Reply(int status, JsonNode body, HttpResponse<String> raw) {
    String code() {
      return body.get("code").asString();
    }
  }

  private Reply send(String method, String path, String body, String idempotencyKey)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    HttpResponse<String> response =
        http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    JsonNode json = response.body().isEmpty() ? null : JSON.readTree(response.body());
    return new Reply(response.statusCode(), json, response);
  }

  private String open(String normalSide, boolean overdraft)
      throws IOException, InterruptedException {
    String code = "acct-" + UUID.randomUUID();
    Reply reply =
        send(
            "POST",
            "/v1/accounts",
            """
            {"code":"%s","name":"Test","currency":"USD","normalSide":"%s","overdraftAllowed":%s}
            """
                .formatted(code, normalSide, overdraft),
            null);
    assertThat(reply.status()).isEqualTo(201);
    return reply.body().get("id").asString();
  }

  private static String entry(String debitAccount, String creditAccount, String amountJson) {
    return """
        {"currency":"USD","description":"test","postings":[
          {"accountId":"%s","direction":"DEBIT","amountMinor":%s},
          {"accountId":"%s","direction":"CREDIT","amountMinor":%s}]}
        """
        .formatted(debitAccount, amountJson, creditAccount, amountJson);
  }

  @Test
  void aDepositAndATransferReadBackThroughTheApi() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);
    String bob = open("CREDIT", false);

    Reply deposit = send("POST", "/v1/entries", entry(cash, alice, "10000"), "dep-" + cash);
    assertThat(deposit.status()).isEqualTo(201);
    assertThat(deposit.body().get("replayed").asBoolean()).isFalse();

    Reply transfer = send("POST", "/v1/entries", entry(alice, bob, "2550"), "xfer-" + cash);
    assertThat(transfer.status()).isEqualTo(201);

    Reply account = send("GET", "/v1/accounts/" + alice, null, null);
    assertThat(account.body().get("balanceMinor").asLong()).isEqualTo(7450);
    assertThat(account.body().get("balanceDisplay").asString()).isEqualTo("74.50");

    Reply statement = send("GET", "/v1/accounts/" + alice + "/statement", null, null);
    assertThat(statement.body().get("lines").size()).isEqualTo(2);
    JsonNode newest = statement.body().get("lines").get(0);
    assertThat(newest.get("direction").asString()).isEqualTo("DEBIT");
    assertThat(newest.get("amountMinor").asLong()).isEqualTo(2550);
    assertThat(newest.get("balanceAfterMinor").asLong()).isEqualTo(7450);
    assertThat(statement.body().get("nextBefore").isNull()).isTrue();

    Reply read = send("GET", "/v1/entries/" + transfer.body().get("id").asString(), null, null);
    assertThat(read.body().get("postings").size()).isEqualTo(2);

    assertThat(send("GET", "/v1/audit", null, null).body().get("clean").asBoolean()).isTrue();
  }

  @Test
  void aStatementPagesBackwardsThroughEveryLine() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);
    for (int i = 0; i < 5; i++) {
      assertThat(send("POST", "/v1/entries", entry(cash, alice, "1"), "p" + i + cash).status())
          .isEqualTo(201);
    }

    Reply page1 = send("GET", "/v1/accounts/" + alice + "/statement?limit=2", null, null);
    long cursor = page1.body().get("nextBefore").asLong();
    Reply page2 =
        send("GET", "/v1/accounts/" + alice + "/statement?limit=2&before=" + cursor, null, null);
    long cursor2 = page2.body().get("nextBefore").asLong();
    Reply page3 =
        send("GET", "/v1/accounts/" + alice + "/statement?limit=2&before=" + cursor2, null, null);

    assertThat(page1.body().get("lines").get(0).get("balanceAfterMinor").asLong()).isEqualTo(5);
    assertThat(page2.body().get("lines").get(0).get("balanceAfterMinor").asLong()).isEqualTo(3);
    assertThat(page3.body().get("lines").size()).isEqualTo(1);
    assertThat(page3.body().get("nextBefore").isNull()).isTrue();
  }

  @Test
  void aReplayAnswers200WithTheOriginalEntry() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);
    String key = "replay-" + cash;

    Reply first = send("POST", "/v1/entries", entry(cash, alice, "100"), key);
    Reply second = send("POST", "/v1/entries", entry(cash, alice, "100"), key);

    assertThat(first.status()).isEqualTo(201);
    assertThat(second.status()).isEqualTo(200);
    assertThat(second.body().get("id")).isEqualTo(first.body().get("id"));
    assertThat(second.body().get("replayed").asBoolean()).isTrue();
  }

  @Test
  void refusalsAreProblemDocumentsWithStableCodes() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);
    String bob = open("CREDIT", false);

    Reply overdraft = send("POST", "/v1/entries", entry(alice, bob, "1"), "od-" + cash);
    assertThat(overdraft.status()).isEqualTo(422);
    assertThat(overdraft.code()).isEqualTo("insufficient_funds");
    assertThat(overdraft.raw().headers().firstValue("Content-Type"))
        .contains("application/problem+json");

    String unbalanced =
        """
        {"currency":"USD","description":"skew","postings":[
          {"accountId":"%s","direction":"DEBIT","amountMinor":100},
          {"accountId":"%s","direction":"CREDIT","amountMinor":99}]}
        """
            .formatted(cash, alice);
    Reply skew = send("POST", "/v1/entries", unbalanced, "skew-" + cash);
    assertThat(skew.status()).isEqualTo(422);
    assertThat(skew.code()).isEqualTo("unbalanced_entry");

    send("POST", "/v1/entries", entry(cash, alice, "100"), "reuse-" + cash);
    Reply reused = send("POST", "/v1/entries", entry(cash, alice, "200"), "reuse-" + cash);
    assertThat(reused.status()).isEqualTo(409);
    assertThat(reused.code()).isEqualTo("idempotency_key_reused");

    Reply missing = send("GET", "/v1/accounts/" + UUID.randomUUID(), null, null);
    assertThat(missing.status()).isEqualTo(404);
    assertThat(missing.code()).isEqualTo("account_not_found");
  }

  @Test
  void anEntryNeedsAnIdempotencyKey() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);

    assertThat(send("POST", "/v1/entries", entry(cash, alice, "100"), null).status())
        .isEqualTo(400);
  }

  @Test
  void aDecimalAmountIsRefusedNotTruncated() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);

    Reply reply = send("POST", "/v1/entries", entry(cash, alice, "10.5"), "float-" + cash);

    assertThat(reply.status()).isEqualTo(400);
    assertThat(send("GET", "/v1/accounts/" + alice, null, null).body().get("balanceMinor").asLong())
        .isZero();
  }

  @Test
  void aStringAmountIsRefused() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);

    assertThat(send("POST", "/v1/entries", entry(cash, alice, "\"100\""), "str-" + cash).status())
        .isEqualTo(400);
  }

  @Test
  void anAmountBeyondTheSupportedRangeIsRefused() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);

    Reply reply =
        send("POST", "/v1/entries", entry(cash, alice, "9007199254740992"), "big-" + cash);

    assertThat(reply.status()).isEqualTo(422);
    assertThat(reply.code()).isEqualTo("invalid_entry");
  }

  @Test
  void anUnknownFieldIsRefusedInsteadOfIgnored() throws Exception {
    String body =
        """
        {"code":"typo","name":"t","currency":"USD","normalSide":"DEBIT","overdraftAlowed":true}
        """;

    assertThat(send("POST", "/v1/accounts", body, null).status()).isEqualTo(400);
  }

  @Test
  void aDuplicateAccountCodeConflicts() throws Exception {
    String code = "dup-" + UUID.randomUUID();
    String body =
        """
        {"code":"%s","name":"t","currency":"USD","normalSide":"DEBIT","overdraftAllowed":false}
        """
            .formatted(code);

    assertThat(send("POST", "/v1/accounts", body, null).status()).isEqualTo(201);
    Reply second = send("POST", "/v1/accounts", body, null);
    assertThat(second.status()).isEqualTo(409);
    assertThat(second.code()).isEqualTo("account_code_taken");
  }

  @Test
  void anUnknownCurrencyIsRefused() throws Exception {
    String body =
        """
        {"code":"btc-%s","name":"t","currency":"BTC","normalSide":"DEBIT","overdraftAllowed":false}
        """
            .formatted(UUID.randomUUID());

    Reply reply = send("POST", "/v1/accounts", body, null);

    assertThat(reply.status()).isEqualTo(422);
    assertThat(reply.code()).isEqualTo("unknown_currency");
  }

  @Test
  void aReversalThroughTheApiCanHappenOnce() throws Exception {
    String cash = open("DEBIT", true);
    String alice = open("CREDIT", false);
    Reply deposit = send("POST", "/v1/entries", entry(cash, alice, "100"), "rv-" + cash);
    String id = deposit.body().get("id").asString();

    Reply reversal = send("POST", "/v1/entries/" + id + "/reversal", null, "undo-" + cash);
    Reply again = send("POST", "/v1/entries/" + id + "/reversal", null, "undo2-" + cash);

    assertThat(reversal.status()).isEqualTo(201);
    assertThat(reversal.body().get("reversesEntryId").asString()).isEqualTo(id);
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.code()).isEqualTo("already_reversed");
  }

  @Test
  void aStatementPageSizeOutOfRangeIsRefused() throws Exception {
    String alice = open("CREDIT", false);

    assertThat(send("GET", "/v1/accounts/" + alice + "/statement?limit=0", null, null).status())
        .isEqualTo(400);
    assertThat(send("GET", "/v1/accounts/" + alice + "/statement?limit=201", null, null).status())
        .isEqualTo(400);
  }

  /** These bounds fail silently if misconfigured, so the running service is asked directly. */
  @Test
  void connectionsCarryTheLockAndStatementTimeouts() {
    assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("2s");
    assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("10s");
  }

  @Test
  void healthIsUp() throws Exception {
    assertThat(send("GET", "/actuator/health", null, null).body().get("status").asString())
        .isEqualTo("UP");
  }

  /** The committed spec is the public contract, so a code change that alters it must say so. */
  @Test
  void theCommittedOpenApiSpecMatchesTheRunningService() throws Exception {
    JsonNode live = send("GET", "/v3/api-docs", null, null).body();
    JsonMapper pretty = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    ((tools.jackson.databind.node.ArrayNode) live.get("servers")).removeAll();

    if (Boolean.getBoolean("ledgerline.writeOpenApi")) {
      Files.writeString(OPENAPI_SPEC, pretty.writeValueAsString(live) + "\n");
    }

    assertThat(live)
        .as("regenerate with ./gradlew test --tests '*LedgerApiTest' -PwriteOpenApi")
        .isEqualTo(JSON.readTree(Files.readString(OPENAPI_SPEC)));
  }
}
