package io.github.matejpopovski.ledgerline.posting;

import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ALREADY_REVERSED;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.ENTRY_NOT_FOUND;
import static io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason.IDEMPOTENCY_KEY_REUSED;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.Leg;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.domain.Posting;
import io.github.matejpopovski.ledgerline.domain.PostingPlan;
import io.github.matejpopovski.ledgerline.outbox.OutboxStore;
import io.github.matejpopovski.ledgerline.store.LedgerStore;
import io.github.matejpopovski.ledgerline.store.LedgerStore.EntryClaim;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The write path. Everything that changes the ledger goes through {@link #post}. */
public class PostingService {

  private final LedgerStore store;
  private final OutboxStore outbox;
  private final RetryingTransactions transactions;
  private final ConcurrencyStrategy strategy;
  private final MeterRegistry meters;

  public PostingService(
      LedgerStore store,
      OutboxStore outbox,
      RetryingTransactions transactions,
      ConcurrencyStrategy strategy,
      MeterRegistry meters) {
    this.store = store;
    this.outbox = outbox;
    this.transactions = transactions;
    this.strategy = strategy;
    this.meters = meters;
  }

  /**
   * Posts an entry exactly once per idempotency key. The balance writes, journal rows and outbox
   * event commit together or not at all.
   */
  public PostedEntry post(EntryDraft draft) {
    String fingerprint = draft.fingerprint();
    PostedEntry result = transactions.execute(() -> postOnce(draft, fingerprint));
    meters
        .counter("ledger.entries", "outcome", result.replayed() ? "replayed" : "posted")
        .increment();
    return result;
  }

  /**
   * Posts the mirror image of an entry. History is never edited, so this is the only correction
   * there is, and it can bounce: reversing a deposit that has been spent is an overdraft.
   */
  public PostedEntry reverse(UUID entryId, String idempotencyKey) {
    PostedEntry original = find(entryId);
    List<Leg> mirrored =
        original.postings().stream()
            .map(p -> new Leg(p.accountId(), p.direction().opposite(), p.amountMinor()))
            .toList();
    return post(
        new EntryDraft(
            idempotencyKey,
            original.currency(),
            "Reversal of entry " + original.sequence(),
            mirrored,
            original.id()));
  }

  public PostedEntry find(UUID entryId) {
    return store
        .findEntry(entryId)
        .orElseThrow(() -> new LedgerRejection(ENTRY_NOT_FOUND, "no such entry: " + entryId))
        .entry();
  }

  private PostedEntry postOnce(EntryDraft draft, String fingerprint) {
    UUID entryId = UUID.randomUUID();
    Optional<EntryClaim> claim = store.claimEntry(entryId, draft, fingerprint);
    if (claim.isEmpty()) {
      return replay(draft, fingerprint);
    }

    List<UUID> accountIds = draft.accountIdsInLockOrder();
    Map<UUID, Account> accounts =
        strategy.locksRows() ? store.lockAccounts(accountIds) : store.readAccounts(accountIds);

    PostingPlan plan = PostingPlan.of(draft, accounts);
    store.writeBalances(plan);
    store.insertPostings(entryId, draft.currency(), plan);

    PostedEntry posted = toPostedEntry(entryId, draft, claim.get(), plan);
    outbox.enqueue(posted);
    return posted;
  }

  private PostedEntry replay(EntryDraft draft, String fingerprint) {
    LedgerStore.StoredEntry stored =
        store
            .findEntryByKey(draft.idempotencyKey())
            .orElseThrow(
                () ->
                    new LedgerRejection(
                        ALREADY_REVERSED,
                        "entry was already reversed: " + draft.reversesEntryId()));
    if (!stored.fingerprint().equals(fingerprint)) {
      throw new LedgerRejection(
          IDEMPOTENCY_KEY_REUSED, "idempotency key was used for a different request");
    }
    return stored.entry().asReplay();
  }

  private static PostedEntry toPostedEntry(
      UUID entryId, EntryDraft draft, EntryClaim claim, PostingPlan plan) {
    List<Posting> postings =
        plan.lines().stream()
            .map(
                line ->
                    new Posting(
                        line.leg().accountId(),
                        line.leg().direction(),
                        line.leg().amountMinor(),
                        line.balanceAfterMinor()))
            .toList();
    return new PostedEntry(
        entryId,
        claim.sequence(),
        draft.currency(),
        draft.description(),
        draft.reversesEntryId(),
        claim.postedAt(),
        postings,
        false);
  }
}
