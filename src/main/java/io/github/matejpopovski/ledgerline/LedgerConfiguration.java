package io.github.matejpopovski.ledgerline;

import io.github.matejpopovski.ledgerline.audit.AuditService;
import io.github.matejpopovski.ledgerline.outbox.OutboxStore;
import io.github.matejpopovski.ledgerline.posting.AccountService;
import io.github.matejpopovski.ledgerline.posting.PostingService;
import io.github.matejpopovski.ledgerline.posting.RetryPolicy;
import io.github.matejpopovski.ledgerline.posting.RetryingTransactions;
import io.github.matejpopovski.ledgerline.store.LedgerStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Wires the ledger explicitly, so tests and the benchmark can build the same graph by hand. */
@Configuration(proxyBeanMethods = false)
class LedgerConfiguration {

  @Bean
  LedgerStore ledgerStore(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
    return new LedgerStore(jdbcClient, jdbcTemplate);
  }

  @Bean
  OutboxStore outboxStore(JdbcClient jdbcClient, JsonMapper jsonMapper) {
    return new OutboxStore(jdbcClient, jsonMapper);
  }

  @Bean
  RetryingTransactions retryingTransactions(
      PlatformTransactionManager transactionManager,
      LedgerProperties properties,
      MeterRegistry meters) {
    LedgerProperties.Retry retry = properties.retry();
    return new RetryingTransactions(
        transactionManager,
        properties.strategy(),
        new RetryPolicy(retry.maxAttempts(), retry.baseDelay(), retry.maxDelay()),
        meters);
  }

  @Bean
  PostingService postingService(
      LedgerStore store,
      OutboxStore outbox,
      RetryingTransactions transactions,
      LedgerProperties properties,
      MeterRegistry meters) {
    return new PostingService(store, outbox, transactions, properties.strategy(), meters);
  }

  @Bean
  AccountService accountService(LedgerStore store) {
    return new AccountService(store);
  }

  @Bean
  AuditService auditService(JdbcClient jdbcClient, PlatformTransactionManager transactionManager) {
    return new AuditService(jdbcClient, transactionManager);
  }
}
