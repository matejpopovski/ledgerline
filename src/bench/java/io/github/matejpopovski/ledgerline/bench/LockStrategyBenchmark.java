package io.github.matejpopovski.ledgerline.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.matejpopovski.ledgerline.audit.AuditReport;
import io.github.matejpopovski.ledgerline.audit.AuditService;
import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.Leg;
import io.github.matejpopovski.ledgerline.outbox.OutboxStore;
import io.github.matejpopovski.ledgerline.posting.AccountService;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.posting.PostingService;
import io.github.matejpopovski.ledgerline.posting.RetryPolicy;
import io.github.matejpopovski.ledgerline.posting.RetryingTransactions;
import io.github.matejpopovski.ledgerline.store.LedgerStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.HdrHistogram.Histogram;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed-loop throughput of the real posting path on a real Postgres, one cell per combination of
 * strategy, account count and worker count. Each repeat shuffles the grid, so slow drift in machine
 * load cannot line up with one strategy, and every run ends with a full audit: a throughput number
 * only counts if the books still balance afterwards.
 */
public final class LockStrategyBenchmark {

  private static final String TEMPLATE = "bench_template";
  private static final long OPENING_BALANCE = 1_000_000_000_000L;
  private static final RetryPolicy PRODUCTION_RETRIES =
      new RetryPolicy(12, Duration.ofMillis(2), Duration.ofMillis(100));

  private record Options(
      List<ConcurrencyStrategy> strategies,
      List<Integer> accounts,
      List<Integer> workers,
      Duration warmup,
      Duration measure,
      int repeats,
      String synchronousCommit,
      long seed,
      Path out) {

    static Options parse(String[] args) {
      Map<String, String> given = new LinkedHashMap<>();
      for (int i = 0; i + 1 < args.length; i += 2) {
        given.put(args[i], args[i + 1]);
      }
      return new Options(
          Arrays.stream(
                  given
                      .getOrDefault("--strategies", "ROW_LOCK,SERIALIZABLE,REPEATABLE_READ")
                      .split(","))
              .map(ConcurrencyStrategy::valueOf)
              .toList(),
          ints(given.getOrDefault("--accounts", "2,32,1024")),
          ints(given.getOrDefault("--workers", "1,8,32")),
          Duration.ofSeconds(Long.parseLong(given.getOrDefault("--warmup", "2"))),
          Duration.ofSeconds(Long.parseLong(given.getOrDefault("--seconds", "8"))),
          Integer.parseInt(given.getOrDefault("--repeats", "3")),
          given.getOrDefault("--synchronous-commit", "on"),
          Long.parseLong(given.getOrDefault("--seed", "20261102")),
          Path.of(given.getOrDefault("--out", "results/lock-strategies.json")));
    }

    private static List<Integer> ints(String csv) {
      return Arrays.stream(csv.split(",")).map(Integer::valueOf).toList();
    }
  }

  private record Cell(ConcurrencyStrategy strategy, int accounts, int workers, int repeat) {}

  private LockStrategyBenchmark() {}

  public static void main(String[] args) throws Exception {
    Options options = Options.parse(args);
    try (EmbeddedPostgres postgres = start(options)) {
      prepareTemplate(postgres);
      List<Map<String, Object>> runs = new ArrayList<>();
      List<Cell> cells = shuffledGrid(options);
      int done = 0;
      for (Cell cell : cells) {
        Map<String, Object> run = measure(postgres, cell, options);
        runs.add(run);
        done++;
        System.out.printf(
            "[%d/%d] %s accounts=%d workers=%d repeat=%d: %s commits/s, clean=%s%n",
            done,
            cells.size(),
            cell.strategy(),
            cell.accounts(),
            cell.workers(),
            cell.repeat(),
            run.get("commitsPerSecond"),
            run.get("auditClean"));
      }
      write(options, postgres, runs);
    }
  }

  private static EmbeddedPostgres start(Options options) throws IOException {
    return EmbeddedPostgres.builder()
        .setServerConfig("max_connections", "120")
        .setServerConfig("fsync", "on")
        .setServerConfig("full_page_writes", "on")
        .setServerConfig("synchronous_commit", options.synchronousCommit())
        .setServerConfig("shared_buffers", "256MB")
        .start();
  }

  private static void prepareTemplate(EmbeddedPostgres postgres) throws SQLException {
    execute(postgres.getPostgresDatabase(), "CREATE DATABASE " + TEMPLATE);
    Flyway.configure().dataSource(postgres.getDatabase("postgres", TEMPLATE)).load().migrate();
  }

  private static void execute(DataSource dataSource, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static List<Cell> shuffledGrid(Options options) {
    Random random = new Random(options.seed());
    List<Cell> all = new ArrayList<>();
    for (int repeat = 1; repeat <= options.repeats(); repeat++) {
      List<Cell> round = new ArrayList<>();
      for (ConcurrencyStrategy strategy : options.strategies()) {
        for (int accounts : options.accounts()) {
          for (int workers : options.workers()) {
            round.add(new Cell(strategy, accounts, workers, repeat));
          }
        }
      }
      Collections.shuffle(round, random);
      all.addAll(round);
    }
    return all;
  }

  /** The same object graph the application wires, with the same retry policy and timeouts. */
  private static final class Service implements AutoCloseable {
    final HikariDataSource pool;
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final PostingService posting;
    final AccountService accounts;
    final AuditService audit;

    Service(EmbeddedPostgres postgres, String database, ConcurrencyStrategy strategy, int size) {
      HikariConfig config = new HikariConfig();
      config.setJdbcUrl(postgres.getJdbcUrl("postgres", database));
      config.setUsername("postgres");
      config.setMaximumPoolSize(size);
      config.setConnectionInitSql(
          "SET lock_timeout = '2s'; SET statement_timeout = '10s';"
              + " SET idle_in_transaction_session_timeout = '15s'");
      pool = new HikariDataSource(config);
      JdbcClient jdbc = JdbcClient.create(pool);
      JdbcTransactionManager transactions = new JdbcTransactionManager(pool);
      LedgerStore store = new LedgerStore(jdbc, new JdbcTemplate(pool));
      OutboxStore outbox = new OutboxStore(jdbc, JsonMapper.builder().build());
      RetryingTransactions retrying =
          new RetryingTransactions(transactions, strategy, PRODUCTION_RETRIES, meters);
      posting = new PostingService(store, outbox, retrying, strategy, meters);
      accounts = new AccountService(store);
      audit = new AuditService(jdbc, transactions);
    }

    double retries() {
      return meters.find("ledger.transaction.retries").counters().stream()
          .mapToDouble(Counter::count)
          .sum();
    }

    @Override
    public void close() {
      pool.close();
    }
  }

  private static Map<String, Object> measure(EmbeddedPostgres postgres, Cell cell, Options options)
      throws Exception {
    String database = "bench_" + UUID.randomUUID().toString().replace("-", "");
    execute(
        postgres.getPostgresDatabase(), "CREATE DATABASE " + database + " TEMPLATE " + TEMPLATE);
    try (Service service = new Service(postgres, database, cell.strategy(), cell.workers() + 1)) {
      List<Account> wallets = openWallets(service, cell.accounts());
      double loadBefore = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();

      Histogram latency = new Histogram(Duration.ofSeconds(30).toNanos(), 3);
      long[] totals = new long[2];
      double[] retries = new double[1];
      runWorkers(service, wallets, cell, options, latency, totals, retries);

      double loadAfter = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
      AuditReport audit = service.audit.run();
      double seconds = options.measure().toNanos() / 1e9;

      Map<String, Object> run = new LinkedHashMap<>();
      run.put("strategy", cell.strategy().name());
      run.put("accounts", cell.accounts());
      run.put("workers", cell.workers());
      run.put("repeat", cell.repeat());
      run.put("seconds", seconds);
      run.put("commits", totals[0]);
      run.put("gaveUp", totals[1]);
      run.put("retries", (long) retries[0]);
      run.put("commitsPerSecond", Math.round(totals[0] / seconds));
      run.put("retriesPerCommit", totals[0] == 0 ? null : round3(retries[0] / totals[0]));
      run.put("p50Millis", millis(latency.getValueAtPercentile(50)));
      run.put("p99Millis", millis(latency.getValueAtPercentile(99)));
      run.put("maxMillis", millis(latency.getMaxValue()));
      run.put("loadAverageBefore", round3(loadBefore));
      run.put("loadAverageAfter", round3(loadAfter));
      run.put("auditEntries", audit.entries());
      run.put("auditPostings", audit.postings());
      run.put("auditClean", audit.clean());
      return run;
    } finally {
      execute(postgres.getPostgresDatabase(), "DROP DATABASE IF EXISTS " + database);
    }
  }

  private static List<Account> openWallets(Service service, int count) {
    Account treasury = service.accounts.open("treasury", "t", "USD", Direction.DEBIT, true);
    List<Account> wallets = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Account wallet = service.accounts.open("w" + i, "w", "USD", Direction.CREDIT, false);
      service.posting.post(entry(treasury, wallet, OPENING_BALANCE));
      wallets.add(wallet);
    }
    return wallets;
  }

  private static EntryDraft entry(Account debit, Account credit, long amount) {
    return EntryDraft.of(
        UUID.randomUUID().toString(),
        "USD",
        "bench",
        List.of(
            new Leg(debit.id(), Direction.DEBIT, amount),
            new Leg(credit.id(), Direction.CREDIT, amount)));
  }

  private static void runWorkers(
      Service service,
      List<Account> wallets,
      Cell cell,
      Options options,
      Histogram merged,
      long[] totals,
      double[] retries)
      throws InterruptedException {
    AtomicBoolean measuring = new AtomicBoolean();
    AtomicBoolean stop = new AtomicBoolean();
    AtomicInteger failedWorkers = new AtomicInteger();
    CountDownLatch finished = new CountDownLatch(cell.workers());
    long[][] counts = new long[cell.workers()][2];
    Histogram[] histograms = new Histogram[cell.workers()];

    for (int w = 0; w < cell.workers(); w++) {
      int worker = w;
      histograms[w] = new Histogram(Duration.ofSeconds(30).toNanos(), 3);
      Thread.ofPlatform()
          .daemon()
          .start(
              () -> {
                try {
                  work(service, wallets, measuring, stop, histograms[worker], counts[worker]);
                } catch (RuntimeException e) {
                  failedWorkers.incrementAndGet();
                  e.printStackTrace();
                } finally {
                  finished.countDown();
                }
              });
    }

    Thread.sleep(options.warmup());
    double retriesAtStart = service.retries();
    measuring.set(true);
    Thread.sleep(options.measure());
    measuring.set(false);
    retries[0] = service.retries() - retriesAtStart;
    stop.set(true);
    finished.await();

    if (failedWorkers.get() > 0) {
      throw new IllegalStateException(failedWorkers + " workers died; this run is not valid");
    }
    for (int w = 0; w < cell.workers(); w++) {
      merged.add(histograms[w]);
      totals[0] += counts[w][0];
      totals[1] += counts[w][1];
    }
  }

  private static void work(
      Service service,
      List<Account> wallets,
      AtomicBoolean measuring,
      AtomicBoolean stop,
      Histogram latency,
      long[] counts) {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    while (!stop.get()) {
      int from = random.nextInt(wallets.size());
      int to = (from + 1 + random.nextInt(wallets.size() - 1)) % wallets.size();
      EntryDraft draft = entry(wallets.get(from), wallets.get(to), 1);
      long started = System.nanoTime();
      try {
        service.posting.post(draft);
        if (measuring.get()) {
          counts[0]++;
          latency.recordValue(
              Math.min(System.nanoTime() - started, latency.getHighestTrackableValue()));
        }
      } catch (LedgerRejection refused) {
        if (refused.reason() != LedgerRejection.Reason.CONTENTION_LIMIT) {
          throw refused;
        }
        if (measuring.get()) {
          counts[1]++;
        }
      }
    }
  }

  private static double millis(long nanos) {
    return round3(nanos / 1e6);
  }

  private static double round3(double value) {
    return Math.round(value * 1000) / 1000.0;
  }

  private static void write(
      Options options, EmbeddedPostgres postgres, List<Map<String, Object>> runs) throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("command", "./gradlew bench -PbenchArgs=\"" + describe(options) + "\"");
    result.put("finishedAt", Instant.now().toString());
    result.put("machine", machine());
    result.put("postgres", settings(postgres));
    result.put(
        "workload",
        "Closed loop. Each worker posts a two-leg transfer of 1 minor unit between two random"
            + " distinct wallets through PostingService, with a fresh idempotency key and an"
            + " outbox event. Fresh database for every run. Retry policy: 12 attempts, 2 to 100 ms"
            + " full jitter.");
    result.put("runs", runs);
    JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    Files.createDirectories(options.out().toAbsolutePath().getParent());
    Files.writeString(options.out(), json.writeValueAsString(result) + "\n");
  }

  private static String describe(Options o) {
    return String.join(
        " ",
        "--strategies",
        String.join(",", o.strategies().stream().map(Enum::name).toList()),
        "--accounts",
        String.join(",", o.accounts().stream().map(String::valueOf).toList()),
        "--workers",
        String.join(",", o.workers().stream().map(String::valueOf).toList()),
        "--warmup",
        Long.toString(o.warmup().toSeconds()),
        "--seconds",
        Long.toString(o.measure().toSeconds()),
        "--repeats",
        Integer.toString(o.repeats()),
        "--synchronous-commit",
        o.synchronousCommit(),
        "--seed",
        Long.toString(o.seed()),
        "--out",
        o.out().toString());
  }

  private static Map<String, Object> machine() {
    Map<String, Object> machine = new LinkedHashMap<>();
    machine.put("cpu", sysctl("machdep.cpu.brand_string"));
    machine.put("logicalCpus", Runtime.getRuntime().availableProcessors());
    String memory = sysctl("hw.memsize");
    machine.put(
        "memoryGiB", memory.isEmpty() ? null : Long.parseLong(memory) / (1024 * 1024 * 1024));
    machine.put("swapAtEnd", sysctl("vm.swapusage"));
    machine.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
    machine.put("java", System.getProperty("java.version"));
    return machine;
  }

  /** Empty off macOS, where these keys do not exist. */
  private static String sysctl(String key) {
    try {
      Process process = new ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start();
      try (BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line = reader.readLine();
        return process.waitFor() == 0 && line != null ? line.trim() : "";
      }
    } catch (IOException e) {
      return "";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "";
    }
  }

  private static Map<String, Object> settings(EmbeddedPostgres postgres) {
    JdbcClient jdbc = JdbcClient.create(postgres.getPostgresDatabase());
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("version", jdbc.sql("SHOW server_version").query(String.class).single());
    for (String name :
        List.of(
            "fsync",
            "synchronous_commit",
            "wal_sync_method",
            "full_page_writes",
            "shared_buffers",
            "autovacuum")) {
      settings.put(name, jdbc.sql("SHOW " + name).query(String.class).single());
    }
    return settings;
  }
}
