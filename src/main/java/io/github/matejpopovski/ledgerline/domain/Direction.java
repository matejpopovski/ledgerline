package io.github.matejpopovski.ledgerline.domain;

/** The side of the ledger a posting lands on. */
public enum Direction {
  DEBIT("D"),
  CREDIT("C");

  private final String code;

  Direction(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public Direction opposite() {
    return this == DEBIT ? CREDIT : DEBIT;
  }

  public static Direction fromCode(String code) {
    return switch (code) {
      case "D" -> DEBIT;
      case "C" -> CREDIT;
      default -> throw new IllegalArgumentException("unknown direction code: " + code);
    };
  }
}
