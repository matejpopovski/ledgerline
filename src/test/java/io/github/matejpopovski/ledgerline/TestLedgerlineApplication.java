package io.github.matejpopovski.ledgerline;

import io.github.matejpopovski.ledgerline.support.TestDatabase;
import java.util.Map;
import org.springframework.boot.SpringApplication;

/**
 * Runs the service against embedded Postgres with no local database installed: {@code ./gradlew
 * bootTestRun}. Data lives only as long as the process.
 */
public final class TestLedgerlineApplication {

  private TestLedgerlineApplication() {}

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(LedgerlineApplication.class);
    application.setDefaultProperties(
        Map.of(
            "spring.datasource.url",
            TestDatabase.freshDatabaseUrl(),
            "spring.datasource.username",
            "postgres"));
    application.run(args);
  }
}
