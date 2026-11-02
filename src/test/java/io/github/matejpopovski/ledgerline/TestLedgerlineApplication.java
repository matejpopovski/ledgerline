package io.github.matejpopovski.ledgerline;

import io.github.matejpopovski.ledgerline.support.TestDatabase;
import org.springframework.boot.SpringApplication;

/**
 * Runs the service against embedded Postgres with no local database installed: {@code ./gradlew
 * bootTestRun}. Data lives only as long as the process.
 */
public final class TestLedgerlineApplication {

  private TestLedgerlineApplication() {}

  public static void main(String[] args) {
    // System properties, because they outrank application.yaml and default properties do not.
    System.setProperty("spring.datasource.url", TestDatabase.freshDatabaseUrl());
    System.setProperty("spring.datasource.username", "postgres");
    SpringApplication.run(LedgerlineApplication.class, args);
  }
}
