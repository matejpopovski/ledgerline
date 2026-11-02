package io.github.matejpopovski.ledgerline.domain;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Rules for money amounts.
 *
 * <p>The ledger computes in {@code long} minor units and never in decimals. A balance is a sum, and
 * integer sums are exact, overflow detectably, compare with {@code ==}, and map straight onto a
 * Postgres {@code bigint} CHECK constraint. {@link BigDecimal} appears only in {@link #display},
 * where the currency's exponent matters: the arithmetic never needs to know that JPY has no minor
 * unit and KWD has three.
 */
public final class MinorUnits {

  /** 2^53 - 1: the largest integer every JSON client can hold without rounding it. */
  public static final long MAX = 9_007_199_254_740_991L;

  private MinorUnits() {}

  public static String display(long minor, String currencyCode) {
    int exponent = Currency.getInstance(currencyCode).getDefaultFractionDigits();
    return BigDecimal.valueOf(minor, Math.max(exponent, 0)).toPlainString();
  }

  public static boolean isKnownCurrency(String code) {
    try {
      Currency.getInstance(code);
      return true;
    } catch (IllegalArgumentException | NullPointerException e) {
      return false;
    }
  }
}
