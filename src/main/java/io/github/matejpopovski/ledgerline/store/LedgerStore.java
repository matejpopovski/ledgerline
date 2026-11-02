package io.github.matejpopovski.ledgerline.store;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.domain.Posting;
import io.github.matejpopovski.ledgerline.domain.PostingPlan;
import io.github.matejpopovski.ledgerline.domain.StatementLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

/** All SQL for accounts and the journal. Hand-written so the locking is visible in the text. */
public class LedgerStore {

  private static final String ACCOUNT_COLUMNS =
      "id, code, name, currency, normal_side, overdraft_allowed, balance_minor, created_at";

  private static final RowMapper<Account> ACCOUNT =
      (rs, row) ->
          new Account(
              rs.getObject("id", UUID.class),
              rs.getString("code"),
              rs.getString("name"),
              rs.getString("currency"),
              Direction.fromCode(rs.getString("normal_side")),
              rs.getBoolean("overdraft_allowed"),
              rs.getLong("balance_minor"),
              instant(rs, "created_at"));

  private static final RowMapper<Posting> POSTING =
      (rs, row) ->
          new Posting(
              rs.getObject("account_id", UUID.class),
              Direction.fromCode(rs.getString("direction")),
              rs.getLong("amount_minor"),
              rs.getLong("balance_after_minor"));

  private final JdbcClient jdbc;
  private final JdbcTemplate batch;

  public LedgerStore(JdbcClient jdbc, JdbcTemplate batch) {
    this.jdbc = jdbc;
    this.batch = batch;
  }

  /** The identity columns of a journal row, as assigned by the database. */
  public record EntryClaim(long sequence, Instant postedAt) {}

  /** A stored journal header with the fingerprint of the request that created it. */
  public record StoredEntry(PostedEntry entry, String fingerprint) {}

  public boolean insertAccount(Account account) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO accounts (id, code, name, currency, normal_side, overdraft_allowed)
                VALUES (:id, :code, :name, :currency, :normalSide, :overdraftAllowed)
                ON CONFLICT (code) DO NOTHING
                """)
            .param("id", account.id())
            .param("code", account.code())
            .param("name", account.name())
            .param("currency", account.currency())
            .param("normalSide", account.normalSide().code())
            .param("overdraftAllowed", account.overdraftAllowed())
            .update();
    return inserted == 1;
  }

  public Optional<Account> findAccount(UUID id) {
    return jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id = :id")
        .param("id", id)
        .query(ACCOUNT)
        .optional();
  }

  /** Reads accounts without locking. Only safe under an isolation level that detects conflicts. */
  public Map<UUID, Account> readAccounts(Collection<UUID> ids) {
    return byId(
        jdbc.sql("SELECT " + ACCOUNT_COLUMNS + " FROM accounts WHERE id IN (:ids) ORDER BY id")
            .param("ids", ids)
            .query(ACCOUNT)
            .list());
  }

  /**
   * Locks accounts in id order and reads the latest committed balances. {@code FOR NO KEY UPDATE}
   * rather than {@code FOR UPDATE} so foreign-key checks from other entries are not blocked; the
   * sort happens below the lock step, so two entries over the same accounts cannot deadlock.
   */
  public Map<UUID, Account> lockAccounts(Collection<UUID> ids) {
    return byId(
        jdbc.sql(
                "SELECT "
                    + ACCOUNT_COLUMNS
                    + " FROM accounts WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE")
            .param("ids", ids)
            .query(ACCOUNT)
            .list());
  }

  private static Map<UUID, Account> byId(List<Account> accounts) {
    Map<UUID, Account> result = new HashMap<>();
    accounts.forEach(account -> result.put(account.id(), account));
    return result;
  }

  /**
   * Inserts the journal header, or returns empty if a unique constraint already holds this key or
   * reversal. This runs first in the transaction: a second request with the same key blocks here
   * until the first one commits or rolls back, before it can reach a funds check that would see
   * balances the first request has already moved.
   */
  public Optional<EntryClaim> claimEntry(UUID id, EntryDraft draft, String fingerprint) {
    return jdbc.sql(
            """
            INSERT INTO journal_entries
                (id, idempotency_key, fingerprint, currency, description, reverses_entry_id)
            VALUES (:id, :key, :fingerprint, :currency, :description, :reverses)
            ON CONFLICT DO NOTHING
            RETURNING seq, posted_at
            """)
        .param("id", id)
        .param("key", draft.idempotencyKey())
        .param("fingerprint", fingerprint)
        .param("currency", draft.currency())
        .param("description", draft.description())
        .param("reverses", draft.reversesEntryId(), java.sql.Types.OTHER)
        .query((rs, row) -> new EntryClaim(rs.getLong("seq"), instant(rs, "posted_at")))
        .optional();
  }

  /** Writes balances in lock order. The value was computed from the row this transaction read. */
  public void writeBalances(PostingPlan plan) {
    List<PostingPlan.Line> lines = plan.inLockOrder();
    batch.batchUpdate(
        "UPDATE accounts SET balance_minor = ? WHERE id = ?",
        lines,
        lines.size(),
        (statement, line) -> {
          statement.setLong(1, line.balanceAfterMinor());
          statement.setObject(2, line.leg().accountId());
        });
  }

  /**
   * Must run after {@link #writeBalances}. Holding the account row while the identity value is
   * assigned is what makes posting ids per account follow commit order, and the audit's running
   * balance check relies on that.
   */
  public void insertPostings(UUID entryId, String currency, PostingPlan plan) {
    batch.batchUpdate(
        """
        INSERT INTO postings
            (entry_id, account_id, currency, direction, amount_minor, balance_after_minor)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        plan.lines(),
        plan.lines().size(),
        (statement, line) -> {
          statement.setObject(1, entryId);
          statement.setObject(2, line.leg().accountId());
          statement.setString(3, currency);
          statement.setString(4, line.leg().direction().code());
          statement.setLong(5, line.leg().amountMinor());
          statement.setLong(6, line.balanceAfterMinor());
        });
  }

  public Optional<StoredEntry> findEntryByKey(String idempotencyKey) {
    return findEntry("idempotency_key = :value", idempotencyKey);
  }

  public Optional<StoredEntry> findEntry(UUID id) {
    return findEntry("id = :value", id);
  }

  private Optional<StoredEntry> findEntry(String predicate, Object value) {
    return jdbc.sql(
            """
            SELECT id, seq, currency, description, reverses_entry_id, posted_at, fingerprint
            FROM journal_entries WHERE
            """
                + predicate)
        .param("value", value)
        .query(
            (rs, row) -> {
              UUID id = rs.getObject("id", UUID.class);
              return new StoredEntry(
                  new PostedEntry(
                      id,
                      rs.getLong("seq"),
                      rs.getString("currency"),
                      rs.getString("description"),
                      rs.getObject("reverses_entry_id", UUID.class),
                      instant(rs, "posted_at"),
                      List.of(),
                      false),
                  rs.getString("fingerprint"));
            })
        .optional()
        .map(this::withPostings);
  }

  private StoredEntry withPostings(StoredEntry stored) {
    PostedEntry header = stored.entry();
    List<Posting> postings =
        jdbc.sql(
                """
                SELECT account_id, direction, amount_minor, balance_after_minor
                FROM postings WHERE entry_id = :id ORDER BY id
                """)
            .param("id", header.id())
            .query(POSTING)
            .list();
    return new StoredEntry(
        new PostedEntry(
            header.id(),
            header.sequence(),
            header.currency(),
            header.description(),
            header.reversesEntryId(),
            header.postedAt(),
            postings,
            false),
        stored.fingerprint());
  }

  /** Newest first, keyset paged on the posting id, so deep pages stay cheap. */
  public List<StatementLine> statement(UUID accountId, long before, int limit) {
    return jdbc.sql(
            """
            SELECT p.id, p.entry_id, e.posted_at, e.description,
                   p.direction, p.amount_minor, p.balance_after_minor
            FROM postings p
            JOIN journal_entries e ON e.id = p.entry_id
            WHERE p.account_id = :account AND p.id < :before
            ORDER BY p.id DESC
            LIMIT :limit
            """)
        .param("account", accountId)
        .param("before", before)
        .param("limit", limit)
        .query(
            (rs, row) ->
                new StatementLine(
                    rs.getLong("id"),
                    rs.getObject("entry_id", UUID.class),
                    instant(rs, "posted_at"),
                    rs.getString("description"),
                    Direction.fromCode(rs.getString("direction")),
                    rs.getLong("amount_minor"),
                    rs.getLong("balance_after_minor")))
        .list();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp timestamp = rs.getTimestamp(column);
    return timestamp.toInstant();
  }
}
