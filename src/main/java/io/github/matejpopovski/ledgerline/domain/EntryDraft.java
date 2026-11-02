package io.github.matejpopovski.ledgerline.domain;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.INVALID_ENTRY;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.UNBALANCED_ENTRY;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.UNKNOWN_CURRENCY;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A journal entry that is well formed but not yet posted. Constructing one proves everything that
 * can be proved without reading the database, including that debits equal credits.
 */
public record EntryDraft(
    String idempotencyKey,
    String currency,
    String description,
    List<Leg> legs,
    UUID reversesEntryId) {

  public static final int MAX_LEGS = 64;
  public static final int MAX_KEY_LENGTH = 128;
  public static final int MAX_DESCRIPTION_LENGTH = 256;

  public EntryDraft {
    legs = List.copyOf(legs);
    requireValidKey(idempotencyKey);
    if (!MinorUnits.isKnownCurrency(currency)) {
      throw new LedgerRejection(UNKNOWN_CURRENCY, "not an ISO 4217 currency: " + currency);
    }
    if (description.isBlank() || description.length() > MAX_DESCRIPTION_LENGTH) {
      throw new LedgerRejection(
          INVALID_ENTRY, "description must be 1 to " + MAX_DESCRIPTION_LENGTH + " characters");
    }
    requireBalancedLegs(legs);
  }

  public static EntryDraft of(
      String idempotencyKey, String currency, String description, List<Leg> legs) {
    return new EntryDraft(idempotencyKey, currency, description, legs, null);
  }

  private static void requireValidKey(String key) {
    if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
      throw new LedgerRejection(
          INVALID_ENTRY, "idempotency key must be 1 to " + MAX_KEY_LENGTH + " characters");
    }
    if (!key.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
      throw new LedgerRejection(INVALID_ENTRY, "idempotency key must be printable ASCII");
    }
  }

  private static void requireBalancedLegs(List<Leg> legs) {
    if (legs.size() < 2 || legs.size() > MAX_LEGS) {
      throw new LedgerRejection(INVALID_ENTRY, "an entry needs 2 to " + MAX_LEGS + " postings");
    }
    Set<UUID> seen = new HashSet<>();
    long net = 0;
    for (Leg leg : legs) {
      if (leg.amountMinor() <= 0 || leg.amountMinor() > MinorUnits.MAX) {
        throw new LedgerRejection(
            INVALID_ENTRY, "amounts must be between 1 and " + MinorUnits.MAX + " minor units");
      }
      if (!seen.add(leg.accountId())) {
        throw new LedgerRejection(
            INVALID_ENTRY, "account appears twice in one entry: " + leg.accountId());
      }
      net = Math.addExact(net, leg.signedAmount());
    }
    if (net != 0) {
      throw new LedgerRejection(UNBALANCED_ENTRY, "debits and credits differ by " + net);
    }
  }

  /** Account ids in lock order. Every writer must touch accounts in this order. */
  public List<UUID> accountIdsInLockOrder() {
    return legs.stream().map(Leg::accountId).sorted().toList();
  }

  /**
   * SHA-256 of the request's meaning, so a key reused for a different request is detected. Legs are
   * sorted because reordering them does not change what the entry does.
   */
  public String fingerprint() {
    StringBuilder canonical = new StringBuilder();
    appendField(canonical, currency);
    appendField(canonical, description);
    appendField(canonical, String.valueOf(reversesEntryId));
    legs.stream()
        .sorted(Comparator.comparing(Leg::accountId))
        .forEach(
            leg -> {
              appendField(canonical, leg.accountId().toString());
              appendField(canonical, leg.direction().code());
              appendField(canonical, Long.toString(leg.amountMinor()));
            });
    return HexFormat.of().formatHex(sha256(canonical.toString()));
  }

  /** Length-prefixed, so that no two different field lists serialize to the same string. */
  private static void appendField(StringBuilder out, String value) {
    out.append(value.length()).append(':').append(value).append(';');
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JVM must provide SHA-256", e);
    }
  }
}
