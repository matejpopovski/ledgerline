package io.github.matejpopovski.ledgerline.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A committed journal entry. {@code replayed} is true when the entry predates this request. */
public record PostedEntry(
    UUID id,
    long sequence,
    String currency,
    String description,
    UUID reversesEntryId,
    Instant postedAt,
    List<Posting> postings,
    boolean replayed) {

  public PostedEntry {
    postings = List.copyOf(postings);
  }

  public PostedEntry asReplay() {
    return new PostedEntry(
        id, sequence, currency, description, reversesEntryId, postedAt, postings, true);
  }
}
