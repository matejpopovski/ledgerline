package io.github.matejpopovski.ledgerline.api;

import io.github.matejpopovski.ledgerline.api.ApiModels.AccountView;
import io.github.matejpopovski.ledgerline.api.ApiModels.OpenAccountRequest;
import io.github.matejpopovski.ledgerline.api.ApiModels.StatementView;
import io.github.matejpopovski.ledgerline.domain.StatementLine;
import io.github.matejpopovski.ledgerline.posting.AccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounts")
@Tag(name = "Accounts")
class AccountController {

  private final AccountService accounts;

  AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Open an account with a zero balance")
  AccountView open(@Valid @RequestBody OpenAccountRequest request) {
    return AccountView.of(
        accounts.open(
            request.code(),
            request.name(),
            request.currency(),
            request.normalSide(),
            request.overdraftAllowed()));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Read an account and its current balance")
  AccountView get(@PathVariable UUID id) {
    return AccountView.of(accounts.find(id));
  }

  @GetMapping("/{id}/statement")
  @Operation(summary = "Page through an account's postings, newest first")
  StatementView statement(
      @PathVariable UUID id,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
    List<StatementLine> lines =
        accounts.statement(id, before == null ? Long.MAX_VALUE : before, limit);
    Long nextBefore = lines.size() == limit ? lines.getLast().cursor() : null;
    return new StatementView(lines, nextBefore);
  }
}
