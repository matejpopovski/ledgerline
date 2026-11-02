package io.github.matejpopovski.ledgerline.api;

import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Renders refusals as RFC 9457 problem documents with a stable machine-readable code. */
@RestControllerAdvice
class ProblemResponses extends ResponseEntityExceptionHandler {

  @ExceptionHandler
  ResponseEntity<ProblemDetail> rejected(LedgerRejection rejection) {
    HttpStatus status = statusOf(rejection.reason());
    String code = rejection.reason().name().toLowerCase(Locale.ROOT);
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, rejection.getMessage());
    problem.setType(URI.create("urn:ledgerline:problem:" + code.replace('_', '-')));
    problem.setProperty("code", code);

    ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
    if (status == HttpStatus.SERVICE_UNAVAILABLE) {
      response.header(HttpHeaders.RETRY_AFTER, "1");
    }
    return response.body(problem);
  }

  private static HttpStatus statusOf(LedgerRejection.Reason reason) {
    return switch (reason) {
      case ACCOUNT_NOT_FOUND, ENTRY_NOT_FOUND -> HttpStatus.NOT_FOUND;
      case ACCOUNT_CODE_TAKEN, IDEMPOTENCY_KEY_REUSED, ALREADY_REVERSED -> HttpStatus.CONFLICT;
      case CONTENTION_LIMIT -> HttpStatus.SERVICE_UNAVAILABLE;
      case INVALID_ENTRY,
          UNBALANCED_ENTRY,
          UNKNOWN_CURRENCY,
          CURRENCY_MISMATCH,
          INSUFFICIENT_FUNDS,
          BALANCE_OUT_OF_RANGE ->
          HttpStatus.UNPROCESSABLE_CONTENT;
    };
  }
}
