package io.github.matejpopovski.ledgerline.domain;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ACCOUNT_NOT_FOUND;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.CURRENCY_MISMATCH;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The postings an entry will produce against a set of account balances. Pure computation. */
public record PostingPlan(List<Line> lines) {

  /** One planned posting with the running balance it leaves behind. */
  public record Line(Leg leg, long balanceAfterMinor) {}

  public PostingPlan {
    lines = List.copyOf(lines);
  }

  public static PostingPlan of(EntryDraft draft, Map<UUID, Account> accounts) {
    List<Line> lines = new ArrayList<>(draft.legs().size());
    for (Leg leg : draft.legs()) {
      Account account = accounts.get(leg.accountId());
      if (account == null) {
        throw new LedgerRejection(ACCOUNT_NOT_FOUND, "no such account: " + leg.accountId());
      }
      if (!account.currency().equals(draft.currency())) {
        throw new LedgerRejection(
            CURRENCY_MISMATCH,
            "account %s is in %s, entry is in %s"
                .formatted(account.code(), account.currency(), draft.currency()));
      }
      lines.add(new Line(leg, account.balanceAfter(leg)));
    }
    return new PostingPlan(lines);
  }

  /** Lines in lock order, so that concurrent writers cannot deadlock on account rows. */
  public List<Line> inLockOrder() {
    return lines.stream().sorted(Comparator.comparing(line -> line.leg().accountId())).toList();
  }
}
