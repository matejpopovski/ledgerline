package io.github.matejpopovski.ledgerline.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class MinorUnitsTest {

  @Test
  void rendersEachCurrencyWithItsOwnExponent() {
    assertThat(MinorUnits.display(1050, "USD")).isEqualTo("10.50");
    assertThat(MinorUnits.display(1050, "JPY")).isEqualTo("1050");
    assertThat(MinorUnits.display(1050, "KWD")).isEqualTo("1.050");
    assertThat(MinorUnits.display(-5, "USD")).isEqualTo("-0.05");
  }

  @Test
  void maxSurvivesARoundTripThroughADouble() {
    assertThat((long) (double) MinorUnits.MAX).isEqualTo(MinorUnits.MAX);
  }

  @Property
  void displayIsExactOverTheWholeRange(
      @ForAll @LongRange(min = -MinorUnits.MAX, max = MinorUnits.MAX) long minor) {
    BigDecimal parsed = new BigDecimal(MinorUnits.display(minor, "USD"));

    assertThat(parsed.movePointRight(2).longValueExact()).isEqualTo(minor);
  }

  @Test
  void knowsIsoCurrenciesOnly() {
    assertThat(MinorUnits.isKnownCurrency("EUR")).isTrue();
    assertThat(MinorUnits.isKnownCurrency("eur")).isFalse();
    assertThat(MinorUnits.isKnownCurrency("BTC")).isFalse();
    assertThat(MinorUnits.isKnownCurrency(null)).isFalse();
  }
}
