package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.CURRENCY_MISMATCH;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.UNBALANCED_ENTRY;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertAuditClean;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertRejected;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertSqlState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.matejpopovski.ledgerline.audit.AuditReport.CurrencyTotal;
import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.Leg;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.Race;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/** Every entry sums to zero, so the books as a whole do, and balances equal their journals. */
class BalanceConservationTest {

  private static final String FAKE_ENTRY =
      """
      INSERT INTO journal_entries (id, idempotency_key, fingerprint, currency, description)
      VALUES (:id, :key, repeat('0', 64), 'USD', 'written behind the service')
      """;
  private static final String FAKE_POSTING =
      """
      INSERT INTO postings
          (entry_id, account_id, currency, direction, amount_minor, balance_after_minor)
      VALUES (:entry, :account, 'USD', :direction, :amount, 0)
      """;

  private static void insertEntry(TestLedger ledger, UUID id) {
    ledger.jdbc.sql(FAKE_ENTRY).param("id", id).param("key", id.toString()).update();
  }

  private static void insertPosting(
      TestLedger ledger, UUID entry, Account account, String direction, long amount) {
    ledger
        .jdbc
        .sql(FAKE_POSTING)
        .param("entry", entry)
        .param("account", account.id())
        .param("direction", direction)
        .param("amount", amount)
        .update();
  }

  private static void inTransaction(TestLedger ledger, Runnable work) {
    new TransactionTemplate(ledger.transactions).executeWithoutResult(status -> work.run());
  }

  @Test
  void anUnbalancedEntryIsRefusedWithADomainError() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account a = ledger.fundedWallet("USD", 500);
      Account b = ledger.wallet("USD");
      long entriesBefore = ledger.count("journal_entries");

      assertRejected(
          UNBALANCED_ENTRY,
          () ->
              ledger.posting.post(
                  EntryDraft.of(
                      "skewed",
                      "USD",
                      "skewed",
                      List.of(
                          new Leg(a.id(), Direction.DEBIT, 100),
                          new Leg(b.id(), Direction.CREDIT, 99)))));

      assertThat(ledger.count("journal_entries")).isEqualTo(entriesBefore);
      assertThat(ledger.balance(a)).isEqualTo(500);
    }
  }

  @Test
  void theDatabaseRefusesAnUnbalancedEntryAtCommit() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account a = ledger.wallet("USD");
      Account b = ledger.wallet("USD");
      UUID entry = UUID.randomUUID();

      assertSqlState(
          "LL002",
          () ->
              inTransaction(
                  ledger,
                  () -> {
                    insertEntry(ledger, entry);
                    insertPosting(ledger, entry, a, "D", 100);
                    insertPosting(ledger, entry, b, "C", 99);
                  }));
      assertThat(ledger.count("journal_entries")).isZero();
    }
  }

  @Test
  void theDatabaseRefusesAnEntryWithNoPostings() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      assertSqlState(
          "LL002", () -> inTransaction(ledger, () -> insertEntry(ledger, UUID.randomUUID())));
    }
  }

  @Test
  void theDatabaseRefusesALegAddedToACommittedEntry() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account wallet = ledger.fundedWallet("USD", 100);
      Account other = ledger.wallet("USD");
      PostedEntry posted = ledger.move("m", wallet, other, 10);

      assertSqlState(
          "LL002",
          () ->
              inTransaction(
                  ledger, () -> insertPosting(ledger, posted.id(), ledger.wallet("USD"), "C", 1)));
    }
  }

  @Test
  void anEntryCannotMixCurrenciesEvenBehindTheService() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account usd = ledger.fundedWallet("USD", 100);
      Account eur = ledger.wallet("EUR");

      assertRejected(CURRENCY_MISMATCH, () -> ledger.move("x", usd, eur, 10));

      UUID entry = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  inTransaction(
                      ledger,
                      () -> {
                        insertEntry(ledger, entry);
                        insertPosting(ledger, entry, eur, "C", 10);
                      }))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("postings_account_fk");
    }
  }

  @StrategiesTest
  void aStormOfRandomTransfersConservesEveryCurrency(ConcurrencyStrategy strategy) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      int accountCount = 6;
      long startingBalance = 10_000;
      List<Account> wallets =
          IntStream.range(0, accountCount)
              .mapToObj(i -> ledger.fundedWallet("USD", startingBalance))
              .toList();

      Race.run(
          8,
          racer -> {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int i = 0; i < 60; i++) {
              Account from = wallets.get(random.nextInt(accountCount));
              Account to = wallets.get(random.nextInt(accountCount));
              if (!from.id().equals(to.id())) {
                ledger.move(UUID.randomUUID().toString(), from, to, random.nextLong(1, 400));
              }
            }
            return null;
          });

      long total = wallets.stream().mapToLong(ledger::balance).sum();
      assertThat(total).isEqualTo(accountCount * startingBalance);
      assertAuditClean(ledger);
      CurrencyTotal usd = ledger.auditReport().trialBalance().getFirst();
      assertThat(usd.debitsMinor()).isEqualTo(usd.creditsMinor());
    }
  }

  @Test
  void aMultiLegEntryMovesAllOfItsAccountsAtomically() {
    try (TestLedger ledger = TestLedger.create(ConcurrencyStrategy.ROW_LOCK)) {
      Account source = ledger.fundedWallet("USD", 100);
      Account shareA = ledger.wallet("USD");
      Account shareB = ledger.wallet("USD");
      Account empty = ledger.wallet("USD");

      ledger.posting.post(
          EntryDraft.of(
              "split",
              "USD",
              "split",
              List.of(
                  new Leg(source.id(), Direction.DEBIT, 100),
                  new Leg(shareA.id(), Direction.CREDIT, 70),
                  new Leg(shareB.id(), Direction.CREDIT, 30))));

      assertThat(ledger.balance(shareA)).isEqualTo(70);
      assertThat(ledger.balance(shareB)).isEqualTo(30);

      long entriesBefore = ledger.count("journal_entries");
      assertThatThrownBy(
              () ->
                  ledger.posting.post(
                      EntryDraft.of(
                          "bounce",
                          "USD",
                          "one leg can pay, the other cannot",
                          List.of(
                              new Leg(shareA.id(), Direction.DEBIT, 70),
                              new Leg(empty.id(), Direction.DEBIT, 1),
                              new Leg(shareB.id(), Direction.CREDIT, 71)))))
          .isInstanceOf(RuntimeException.class);
      assertThat(ledger.balance(shareA)).isEqualTo(70);
      assertThat(ledger.count("journal_entries")).isEqualTo(entriesBefore);
      assertAuditClean(ledger);
    }
  }
}
