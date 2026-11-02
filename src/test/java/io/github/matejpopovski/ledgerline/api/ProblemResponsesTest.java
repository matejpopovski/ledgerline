package io.github.matejpopovski.ledgerline.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.matejpopovski.ledgerline.domain.LedgerRejection;
import io.github.matejpopovski.ledgerline.domain.LedgerRejection.Reason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

class ProblemResponsesTest {

  private final ProblemResponses responses = new ProblemResponses();

  @ParameterizedTest
  @EnumSource(Reason.class)
  void everyReasonIsAClientOrRetryableStatusWithACode(Reason reason) {
    ResponseEntity<ProblemDetail> response =
        responses.rejected(new LedgerRejection(reason, "detail"));

    assertThat(response.getStatusCode().value()).isIn(404, 409, 422, 503);
    assertThat(response.getBody().getProperties()).containsKey("code");
  }

  @Test
  void contentionTellsTheClientToComeBack() {
    ResponseEntity<ProblemDetail> response =
        responses.rejected(new LedgerRejection(Reason.CONTENTION_LIMIT, "busy"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
  }
}
