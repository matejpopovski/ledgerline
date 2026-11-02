package io.github.matejpopovski.ledgerline.posting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

  private static final RetryPolicy POLICY =
      new RetryPolicy(12, Duration.ofMillis(2), Duration.ofMillis(100));

  @Property
  void delaysNeverExceedTheCap(
      @ForAll @IntRange(min = 1, max = 1000) int attempt, @ForAll long seed) {
    Duration delay = POLICY.delayAfter(attempt, new Random(seed));

    assertThat(delay).isBetween(Duration.ZERO, Duration.ofMillis(100));
  }

  @Test
  void theCeilingDoublesUntilItReachesTheCap() {
    Duration first = maxObserved(1);
    Duration fourth = maxObserved(4);
    Duration tenth = maxObserved(10);

    assertThat(first).isLessThanOrEqualTo(Duration.ofMillis(2));
    assertThat(fourth).isGreaterThan(Duration.ofMillis(8));
    assertThat(fourth).isLessThanOrEqualTo(Duration.ofMillis(16));
    assertThat(tenth).isGreaterThan(Duration.ofMillis(90));
  }

  private static Duration maxObserved(int attempt) {
    Random random = new Random(42);
    Duration max = Duration.ZERO;
    for (int i = 0; i < 1000; i++) {
      Duration delay = POLICY.delayAfter(attempt, random);
      max = delay.compareTo(max) > 0 ? delay : max;
    }
    return max;
  }
}
