package io.github.matejpopovski.ledgerline.audit;

import io.github.matejpopovski.ledgerline.audit.AuditReport.AccountDrift;
import io.github.matejpopovski.ledgerline.audit.AuditReport.CurrencyTotal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes every balance from the journal and compares. The queries scan both tables, so this is
 * a scheduled check rather than something to run per request.
 */
public class AuditService {

  private static final String SIGNED_FOR_ACCOUNT =
      "CASE WHEN p.direction = a.normal_side THEN p.amount_minor ELSE -p.amount_minor END";

  private final JdbcClient jdbc;
  private final TransactionTemplate snapshot;

  public AuditService(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.snapshot = new TransactionTemplate(transactionManager);
    // Each comparison below is a single statement and so agrees with itself. The shared
    // snapshot is what lets the counts and totals in one report be compared to each other.
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.snapshot.setReadOnly(true);
  }

  public AuditReport run() {
    return snapshot.execute(status -> report());
  }

  private AuditReport report() {
    return new AuditReport(
        Instant.now(),
        count("SELECT count(*) FROM journal_entries"),
        count("SELECT count(*) FROM postings"),
        trialBalance(),
        count(
            """
            SELECT count(*) FROM (
                SELECT e.id
                FROM journal_entries e
                LEFT JOIN postings p ON p.entry_id = e.id
                GROUP BY e.id
                HAVING count(p.id) < 2
                    OR sum(CASE p.direction WHEN 'D' THEN p.amount_minor
                                            ELSE -p.amount_minor END) <> 0) unbalanced
            """),
        driftedAccounts(),
        count(
            """
            SELECT count(*) FROM (
                SELECT p.balance_after_minor AS after, %s AS delta,
                       lag(p.balance_after_minor, 1, 0::bigint)
                           OVER (PARTITION BY p.account_id ORDER BY p.id) AS before
                FROM postings p
                JOIN accounts a ON a.id = p.account_id) chain
            WHERE before + delta <> after
            """
                .formatted(SIGNED_FOR_ACCOUNT)),
        count(
            """
            SELECT count(*) FROM (
                SELECT a.id
                FROM accounts a
                JOIN postings p ON p.account_id = a.id
                WHERE NOT a.overdraft_allowed
                GROUP BY a.id
                HAVING sum(%s) < 0) overdrawn
            """
                .formatted(SIGNED_FOR_ACCOUNT)),
        count("SELECT count(*) FROM outbox_events WHERE published_at IS NULL"),
        jdbc.sql(
                """
                SELECT floor(extract(epoch FROM now() - min(created_at)))::bigint
                FROM outbox_events WHERE published_at IS NULL
                """)
            .query(Long.class)
            .optional()
            .orElse(null));
  }

  private long count(String sql) {
    return jdbc.sql(sql).query(Long.class).single();
  }

  private List<CurrencyTotal> trialBalance() {
    return jdbc.sql(
            """
            SELECT currency,
                   coalesce(sum(amount_minor) FILTER (WHERE direction = 'D'), 0)::bigint AS debits,
                   coalesce(sum(amount_minor) FILTER (WHERE direction = 'C'), 0)::bigint AS credits
            FROM postings
            GROUP BY currency
            ORDER BY currency
            """)
        .query(
            (rs, row) ->
                new CurrencyTotal(
                    rs.getString("currency"), rs.getLong("debits"), rs.getLong("credits")))
        .list();
  }

  private List<AccountDrift> driftedAccounts() {
    return jdbc.sql(
            """
            SELECT a.id, a.code, a.balance_minor, coalesce(j.total, 0)::bigint AS journal
            FROM accounts a
            LEFT JOIN LATERAL (
                SELECT sum(%s) AS total FROM postings p WHERE p.account_id = a.id) j ON true
            WHERE a.balance_minor <> coalesce(j.total, 0)
            ORDER BY a.code
            """
                .formatted(SIGNED_FOR_ACCOUNT))
        .query(
            (rs, row) ->
                new AccountDrift(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getLong("balance_minor"),
                    rs.getLong("journal")))
        .list();
  }
}
