package io.github.matejpopovski.ledgerline.posting;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ACCOUNT_CODE_TAKEN;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ACCOUNT_NOT_FOUND;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.UNKNOWN_CURRENCY;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.MinorUnits;
import io.github.matejpopovski.ledgerline.domain.StatementLine;
import io.github.matejpopovski.ledgerline.store.LedgerStore;
import java.util.List;
import java.util.UUID;

/** Account lifecycle and read paths. Balances only ever change through {@link PostingService}. */
public class AccountService {

  private final LedgerStore store;

  public AccountService(LedgerStore store) {
    this.store = store;
  }

  public Account open(
      String code, String name, String currency, Direction normalSide, boolean overdraftAllowed) {
    if (!MinorUnits.isKnownCurrency(currency)) {
      throw new LedgerRejection(UNKNOWN_CURRENCY, "not an ISO 4217 currency: " + currency);
    }
    UUID id = UUID.randomUUID();
    Account draft = new Account(id, code, name, currency, normalSide, overdraftAllowed, 0, null);
    if (!store.insertAccount(draft)) {
      throw new LedgerRejection(ACCOUNT_CODE_TAKEN, "account code already exists: " + code);
    }
    return find(id);
  }

  public Account find(UUID id) {
    return store
        .findAccount(id)
        .orElseThrow(() -> new LedgerRejection(ACCOUNT_NOT_FOUND, "no such account: " + id));
  }

  public List<StatementLine> statement(UUID accountId, long before, int limit) {
    find(accountId);
    return store.statement(accountId, before, limit);
  }
}
