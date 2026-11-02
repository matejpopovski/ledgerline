package io.github.matejpopovski.ledgerline.invariants;

import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertAuditClean;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.ConcurrencyStrategy;
import io.github.matejpopovski.ledgerline.support.TestLedger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Random operation sequences run against the real service and against a few lines of plain Java
 * that model what a ledger should do. Any disagreement on any balance fails the property, and jqwik
 * shrinks the sequence to a short counterexample.
 */
class LedgerModelProperties {

  private static final int WALLETS = 4;

  sealed interface Op {}

  record Transfer(int from, int to, long amount) implements Op {}

  record Retry(int ofOperation) implements Op {}

  record Reverse(int ofOperation) implements Op {}

  @Provide
  Arbitrary<List<Op>> operations() {
    Arbitrary<Integer> wallet = Arbitraries.integers().between(0, WALLETS - 1);
    Arbitrary<Long> amount = Arbitraries.longs().between(1, 700);
    Arbitrary<Integer> earlier = Arbitraries.integers().between(0, 40);
    Arbitrary<Op> op =
        Arbitraries.frequencyOf(
            Tuple.of(6, Combinators.combine(wallet, wallet, amount).as(Transfer::new)),
            Tuple.of(2, earlier.map(Retry::new)),
            Tuple.of(1, earlier.map(Reverse::new)));
    return op.list().ofMinSize(1).ofMaxSize(40);
  }

  /** What the model remembers about one committed transfer. */
  private record Posted(PostedEntry entry, int from, int to, long amount) {}

  @Property(tries = 60)
  void theLedgerAgreesWithItsModelUnderRowLocks(@ForAll("operations") List<Op> ops) {
    check(ConcurrencyStrategy.ROW_LOCK, ops);
  }

  @Property(tries = 60)
  void theLedgerAgreesWithItsModelUnderSerializable(@ForAll("operations") List<Op> ops) {
    check(ConcurrencyStrategy.SERIALIZABLE, ops);
  }

  private static void check(ConcurrencyStrategy strategy, List<Op> ops) {
    try (TestLedger ledger = TestLedger.create(strategy)) {
      List<Account> wallets = new ArrayList<>();
      long[] model = new long[WALLETS];
      for (int i = 0; i < WALLETS; i++) {
        wallets.add(ledger.fundedWallet("USD", 1_000));
        model[i] = 1_000;
      }
      List<Posted> history = new ArrayList<>();
      Map<Integer, String> keys = new HashMap<>();
      Set<Integer> reversed = new HashSet<>();

      for (int step = 0; step < ops.size(); step++) {
        switch (ops.get(step)) {
          case Transfer t when t.from() == t.to() -> {}
          case Transfer t -> {
            String key = UUID.randomUUID().toString();
            boolean shouldSucceed = model[t.from()] >= t.amount();
            try {
              PostedEntry entry =
                  ledger.move(key, wallets.get(t.from()), wallets.get(t.to()), t.amount());
              assertThat(shouldSucceed).as("overdraft was accepted").isTrue();
              assertThat(entry.replayed()).isFalse();
              model[t.from()] -= t.amount();
              model[t.to()] += t.amount();
              keys.put(history.size(), key);
              history.add(new Posted(entry, t.from(), t.to(), t.amount()));
            } catch (LedgerRejection refused) {
              assertThat(shouldSucceed).as("valid transfer was refused").isFalse();
            }
          }
          case Retry r when history.isEmpty() -> {}
          case Retry r -> {
            int index = r.ofOperation() % history.size();
            Posted original = history.get(index);
            PostedEntry again =
                ledger.move(
                    keys.get(index),
                    wallets.get(original.from()),
                    wallets.get(original.to()),
                    original.amount());
            assertThat(again.replayed()).isTrue();
            assertThat(again.id()).isEqualTo(original.entry().id());
          }
          case Reverse r when history.isEmpty() -> {}
          case Reverse r -> {
            int index = r.ofOperation() % history.size();
            Posted original = history.get(index);
            boolean shouldSucceed =
                !reversed.contains(index) && model[original.to()] >= original.amount();
            try {
              ledger.posting.reverse(original.entry().id(), UUID.randomUUID().toString());
              assertThat(shouldSucceed).as("invalid reversal was accepted").isTrue();
              reversed.add(index);
              model[original.from()] += original.amount();
              model[original.to()] -= original.amount();
            } catch (LedgerRejection refused) {
              assertThat(shouldSucceed).as("valid reversal was refused").isFalse();
            }
          }
        }
        for (int i = 0; i < WALLETS; i++) {
          assertThat(ledger.balance(wallets.get(i))).as("wallet %d", i).isEqualTo(model[i]);
        }
      }
      assertAuditClean(ledger);
    }
  }
}
