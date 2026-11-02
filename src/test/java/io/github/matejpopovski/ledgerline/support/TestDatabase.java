package io.github.matejpopovski.ledgerline.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * One real Postgres per test JVM, with a fresh database per test. The journal cannot be truncated
 * by design, so tests get isolation from {@code CREATE DATABASE ... TEMPLATE}, which is cheap.
 */
public final class TestDatabase {

  private static final String TEMPLATE = "ledger_template";
  private static final EmbeddedPostgres POSTGRES = start();

  private TestDatabase() {}

  private static EmbeddedPostgres start() {
    try {
      EmbeddedPostgres postgres =
          EmbeddedPostgres.builder().setServerConfig("max_connections", "300").start();
      execute(postgres.getPostgresDatabase(), "CREATE DATABASE " + TEMPLATE);
      Flyway.configure().dataSource(postgres.getDatabase("postgres", TEMPLATE)).load().migrate();
      Runtime.getRuntime().addShutdownHook(new Thread(TestDatabase::stop));
      return postgres;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void stop() {
    try {
      POSTGRES.close();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void execute(DataSource dataSource, String sql) {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(sql, e);
    }
  }

  /** Creates an empty, fully migrated database and returns its JDBC URL. */
  public static String freshDatabaseUrl() {
    String name = "ledger_" + UUID.randomUUID().toString().replace("-", "");
    execute(POSTGRES.getPostgresDatabase(), "CREATE DATABASE " + name + " TEMPLATE " + TEMPLATE);
    return POSTGRES.getJdbcUrl("postgres", name);
  }

  public static HikariDataSource pool(String jdbcUrl, int size, String connectionInitSql) {
    HikariConfig config = new HikariConfig();
    config.setConnectionInitSql(connectionInitSql);
    config.setJdbcUrl(jdbcUrl);
    config.setUsername("postgres");
    config.setMaximumPoolSize(size);
    return new HikariDataSource(config);
  }
}
