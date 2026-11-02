package io.github.matejpopovski.ledgerline.api;

import io.github.matejpopovski.ledgerline.api.ApiModels.PostEntryRequest;
import io.github.matejpopovski.ledgerline.domain.EntryDraft;
import io.github.matejpopovski.ledgerline.domain.Leg;
import io.github.matejpopovski.ledgerline.domain.PostedEntry;
import io.github.matejpopovski.ledgerline.posting.PostingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/entries")
@Tag(name = "Journal")
class EntryController {

  private static final String KEY_HELP =
      "Client-chosen key. Retrying with the same key and body returns the original entry.";

  private final PostingService posting;

  EntryController(PostingService posting) {
    this.posting = posting;
  }

  @PostMapping
  @Operation(summary = "Post a balanced journal entry; 200 instead of 201 means a replay")
  ResponseEntity<PostedEntry> post(
      @Parameter(description = KEY_HELP) @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody PostEntryRequest request) {
    List<Leg> legs =
        request.postings().stream()
            .map(leg -> new Leg(leg.accountId(), leg.direction(), leg.amountMinor()))
            .toList();
    return respond(
        posting.post(EntryDraft.of(key, request.currency(), request.description(), legs)));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Read a posted entry")
  PostedEntry get(@PathVariable UUID id) {
    return posting.find(id);
  }

  @PostMapping("/{id}/reversal")
  @Operation(summary = "Post the mirror image of an entry; an entry can be reversed once")
  ResponseEntity<PostedEntry> reverse(
      @PathVariable UUID id,
      @Parameter(description = KEY_HELP) @RequestHeader("Idempotency-Key") String key) {
    return respond(posting.reverse(id, key));
  }

  private static ResponseEntity<PostedEntry> respond(PostedEntry entry) {
    HttpStatus status = entry.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
    return ResponseEntity.status(status).body(entry);
  }
}
