package io.github.matejpopovski.ledgerline.api;

import io.github.matejpopovski.ledgerline.domain.Account;
import io.github.matejpopovski.ledgerline.domain.Direction;
import io.github.matejpopovski.ledgerline.domain.MinorUnits;
import io.github.matejpopovski.ledgerline.domain.StatementLine;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies. */
final class ApiModels {

  private ApiModels() {}

  record OpenAccountRequest(
      @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9:._-]{0,127}") String code,
      @NotBlank String name,
      @NotNull @Schema(example = "USD") String currency,
      @NotNull @Schema(description = "The side that increases this account's balance.")
          Direction normalSide,
      @NotNull @Schema(description = "Must be stated: nothing about money defaults silently.")
          Boolean overdraftAllowed) {}

  record AccountView(
      UUID id,
      String code,
      String name,
      String currency,
      Direction normalSide,
      boolean overdraftAllowed,
      long balanceMinor,
      @Schema(description = "Balance rendered with the currency's exponent, for people.")
          String balanceDisplay,
      Instant createdAt) {

    static AccountView of(Account account) {
      return new AccountView(
          account.id(),
          account.code(),
          account.name(),
          account.currency(),
          account.normalSide(),
          account.overdraftAllowed(),
          account.balanceMinor(),
          MinorUnits.display(account.balanceMinor(), account.currency()),
          account.createdAt());
    }
  }

  record LegRequest(
      @NotNull UUID accountId,
      @NotNull Direction direction,
      @NotNull
          @Schema(
              description = "Integer minor units: 1050 is 10.50 USD and 1050 JPY.",
              minimum = "1",
              maximum = "9007199254740991")
          Long amountMinor) {}

  record PostEntryRequest(
      @NotNull @Schema(example = "USD") String currency,
      @NotBlank String description,
      @NotEmpty @Valid List<LegRequest> postings) {}

  record StatementView(List<StatementLine> lines, Long nextBefore) {}
}
