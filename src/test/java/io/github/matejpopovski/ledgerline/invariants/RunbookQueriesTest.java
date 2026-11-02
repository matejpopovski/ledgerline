package io.github.matejpopovski.ledgerline.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** A query that stopped matching the schema is found here, not during an incident. */
class RunbookQueriesTest {

  private static final Pattern SQL_BLOCK = Pattern.compile("```sql\\n(.*?)```", Pattern.DOTALL);

  @Test
  void everySqlBlockInTheRunbookRunsAgainstARealLedger() throws IOException {
    List<String> queries =
        SQL_BLOCK
            .matcher(Files.readString(Path.of("docs/RUNBOOK.md")))
            .results()
            .map(match -> match.group(1))
            .toList();
    assertThat(queries).hasSizeGreaterThanOrEqualTo(7);

    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);
      PostedEntry entry = ledger.move("pay", wallet, ledger.wallet("USD"), 10);

      for (String query : queries) {
        new TransactionTemplate(ledger.transactions)
            .executeWithoutResult(
                status -> {
                  JdbcClient.StatementSpec statement = ledger.jdbc.sql(query);
                  if (query.contains(":account_id")) {
                    statement = statement.param("account_id", wallet.id());
                  }
                  if (query.contains(":entry_id")) {
                    statement = statement.param("entry_id", entry.id());
                  }
                  if (query.startsWith("SELECT")) {
                    statement.query().listOfRows();
                  } else {
                    statement.update();
                  }
                  status.setRollbackOnly();
                });
      }
    }
  }
}
