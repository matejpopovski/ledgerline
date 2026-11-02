package io.github.matejpopovski.ledgerline.domain;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INVALID_ENTRY;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.UNBALANCED_ENTRY;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.UNKNOWN_CURRENCY;
import static io.github.matejpopovski.ledgerline.support.LedgerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.LongStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class EntryDraftTest {

  private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
  private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

  private static List<Leg> balancedLegs(List<Long> debitAmounts) {
    List<Leg> legs = new ArrayList<>();
    for (long amount : debitAmounts) {
      legs.add(new Leg(UUID.randomUUID(), Direction.DEBIT, amount));
    }
    long total = debitAmounts.stream().mapToLong(Long::longValue).sum();
    legs.add(new Leg(UUID.randomUUID(), Direction.CREDIT, total));
    return legs;
  }

  @Property
  void everyBalancedSetOfLegsIsAccepted(
      @ForAll @Size(min = 1, max = 16)
          List<@LongRange(min = 1, max = 1_000_000_000L) Long> debits) {
    EntryDraft draft = EntryDraft.of("k", "USD", "d", balancedLegs(debits));

    long net = draft.legs().stream().mapToLong(Leg::signedAmount).sum();
    assertThat(net).isZero();
  }

  @Property
  void anyImbalanceIsRejected(
      @ForAll @Size(min = 1, max = 16) List<@LongRange(min = 1, max = 1_000_000_000L) Long> debits,
      @ForAll @LongRange(min = 1, max = 1_000) long skew) {
    long total = debits.stream().mapToLong(Long::longValue).sum();
    List<Leg> legs = new ArrayList<>();
    debits.forEach(amount -> legs.add(new Leg(UUID.randomUUID(), Direction.DEBIT, amount)));
    legs.add(new Leg(UUID.randomUUID(), Direction.CREDIT, total + skew));

    assertRejected(UNBALANCED_ENTRY, () -> EntryDraft.of("k", "USD", "d", legs));
  }

  @Property
  void fingerprintIgnoresLegOrder(
      @ForAll @Size(min = 1, max = 12) List<@LongRange(min = 1, max = 1_000_000L) Long> debits,
      @ForAll long shuffleSeed) {
    List<Leg> legs = balancedLegs(debits);
    List<Leg> shuffled = new ArrayList<>(legs);
    Collections.shuffle(shuffled, new Random(shuffleSeed));

    assertThat(EntryDraft.of("k", "USD", "d", shuffled).fingerprint())
        .isEqualTo(EntryDraft.of("k", "USD", "d", legs).fingerprint());
  }

  @Test
  void fingerprintChangesWithAnythingThatChangesTheEntry() {
    List<Leg> legs = legs(100);
    String base = EntryDraft.of("k", "USD", "rent", legs).fingerprint();

    assertThat(EntryDraft.of("k", "EUR", "rent", legs).fingerprint()).isNotEqualTo(base);
    assertThat(EntryDraft.of("k", "USD", "rental", legs).fingerprint()).isNotEqualTo(base);
    assertThat(EntryDraft.of("k", "USD", "rent", legs(101)).fingerprint()).isNotEqualTo(base);
    assertThat(new EntryDraft("k", "USD", "rent", legs, A).fingerprint()).isNotEqualTo(base);
  }

  @Test
  void fingerprintIgnoresTheKeyItself() {
    assertThat(EntryDraft.of("k1", "USD", "d", legs(5)).fingerprint())
        .isEqualTo(EntryDraft.of("k2", "USD", "d", legs(5)).fingerprint());
  }

  @Test
  void rejectsMalformedEntries() {
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", "d", List.of()));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", " ", legs(1)));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("", "USD", "d", legs(1)));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("has space", "USD", "d", legs(1)));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k".repeat(129), "USD", "d", legs(1)));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", "d", legs(0)));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", "d", legs(-5)));
    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", "d", legs(MinorUnits.MAX + 1)));
    assertRejected(UNKNOWN_CURRENCY, () -> EntryDraft.of("k", "XXQ", "d", legs(1)));
  }

  @Test
  void rejectsTheSameAccountTwice() {
    List<Leg> legs = List.of(new Leg(A, Direction.DEBIT, 5), new Leg(A, Direction.CREDIT, 5));

    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", "d", legs));
  }

  @Test
  void rejectsTooManyLegs() {
    List<Long> debits = LongStream.range(0, EntryDraft.MAX_LEGS).boxed().map(i -> 1L).toList();

    assertRejected(INVALID_ENTRY, () -> EntryDraft.of("k", "USD", "d", balancedLegs(debits)));
  }

  @Test
  void locksAccountsInIdOrderWhateverTheRequestOrder() {
    EntryDraft draft =
        EntryDraft.of(
            "k",
            "USD",
            "d",
            List.of(new Leg(B, Direction.DEBIT, 1), new Leg(A, Direction.CREDIT, 1)));

    assertThat(draft.accountIdsInLockOrder()).containsExactly(A, B);
  }

  private static List<Leg> legs(long amount) {
    return List.of(new Leg(A, Direction.DEBIT, amount), new Leg(B, Direction.CREDIT, amount));
  }
}
