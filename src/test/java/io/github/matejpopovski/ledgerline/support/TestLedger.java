package io.github.matejpopovski.ledgerline.support;

import com.zaxxer.hikari.HikariDataSource;
import io.github.matejpopovski.ledgerline.audit.AuditReport;
import io.github.matejpopovski.ledgerline.audit.AuditService;
import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.Leg;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.outbox.OutboxStore;
import io.github.matejpopovski.ledgerline.posting.AccountService;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.posting.PostingService;
import io.github.matejpopovski.ledgerline.posting.RetryPolicy;
import io.github.matejpopovski.ledgerline.posting.RetryingTransactions;
import io.github.matejpopovski.ledgerline.store.LedgerStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real service graph over a fresh database, built by hand so a test can pick its strategy. The
 * retry policy is deliberately generous: these tests are about invariants, and an exhausted retry
 * budget under an artificial stampede would only be noise.
 */
public final class TestLedger implements AutoCloseable {

  public static final RetryPolicy PATIENT_RETRIES =
      new RetryPolicy(200, Duration.ofMillis(1), Duration.ofMillis(50));

  private final String jdbcUrl;
  private final HikariDataSource pool;
  private final AtomicInteger codes = new AtomicInteger();

  public final ConcurrencyStrategy strategy;
  public final JdbcClient jdbc;
  public final PlatformTransactionManager transactions;
  public final LedgerStore store;
  public final OutboxStore outbox;
  public final PostingService posting;
  public final AccountService accounts;
  public final AuditService audit;
  public final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private TestLedger(
      String jdbcUrl, ConcurrencyStrategy strategy, RetryPolicy retries, String connectionInitSql) {
    this.jdbcUrl = jdbcUrl;
    this.strategy = strategy;
    this.pool = TestDatabase.pool(jdbcUrl, 40, connectionInitSql);
    this.jdbc = JdbcClient.create(pool);
    this.transactions = new JdbcTransactionManager(pool);
    this.store = new LedgerStore(jdbc, new JdbcTemplate(pool));
    this.outbox = new OutboxStore(jdbc, JsonMapper.builder().build());
    RetryingTransactions retrying =
        new RetryingTransactions(transactions, strategy, retries, meters);
    this.posting = new PostingService(store, outbox, retrying, strategy, meters);
    this.accounts = new AccountService(store);
    this.audit = new AuditService(jdbc, transactions);
  }

  public static TestLedger create(ConcurrencyStrategy strategy) {
    return new TestLedger(TestDatabase.freshDatabaseUrl(), strategy, PATIENT_RETRIES, null);
  }

  public static TestLedger create(ConcurrencyStrategy strategy, RetryPolicy retries) {
    return new TestLedger(TestDatabase.freshDatabaseUrl(), strategy, retries, null);
  }

  public static TestLedger create(
      ConcurrencyStrategy strategy, RetryPolicy retries, String connectionInitSql) {
    return new TestLedger(TestDatabase.freshDatabaseUrl(), strategy, retries, connectionInitSql);
  }

  /** A second service instance on this ledger's database, as during a rolling restart. */
  public TestLedger secondInstance(ConcurrencyStrategy otherStrategy) {
    return new TestLedger(jdbcUrl, otherStrategy, PATIENT_RETRIES, null);
  }

  public HikariDataSource dataSource() {
    return pool;
  }

  /** A customer balance: credit-normal, and not allowed below zero. */
  public Account wallet(String currency) {
    return open(currency, Direction.CREDIT, false);
  }

  /** The counterparty for deposits: debit-normal cash, which may go as far as it needs to. */
  public Account treasury(String currency) {
    return open(currency, Direction.DEBIT, true);
  }

  private Account open(String currency, Direction normalSide, boolean overdraftAllowed) {
    String code = "test-" + codes.incrementAndGet();
    return accounts.open(code, code, currency, normalSide, overdraftAllowed);
  }

  /** A wallet holding the given balance, funded through a real journal entry. */
  public Account fundedWallet(String currency, long balance) {
    Account wallet = wallet(currency);
    deposit(treasury(currency), wallet, balance);
    return wallet;
  }

  public PostedEntry deposit(Account treasury, Account wallet, long amount) {
    return move(UUID.randomUUID().toString(), treasury, wallet, amount);
  }

  /** Debits {@code from} and credits {@code to}: a wallet transfer, or a deposit from treasury. */
  public PostedEntry move(String key, Account from, Account to, long amount) {
    return posting.post(draft(key, from, to, amount));
  }

  public EntryDraft draft(String key, Account from, Account to, long amount) {
    return EntryDraft.of(
        key,
        from.currency(),
        "move " + amount,
        List.of(
            new Leg(from.id(), Direction.DEBIT, amount),
            new Leg(to.id(), Direction.CREDIT, amount)));
  }

  public long balance(Account account) {
    return accounts.find(account.id()).balanceMinor();
  }

  public AuditReport auditReport() {
    return audit.run();
  }

  public long count(String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }

  public double retries(String sqlState) {
    return meters.counter("ledger.transaction.retries", "sqlstate", sqlState).count();
  }

  @Override
  public void close() {
    pool.close();
  }
}
