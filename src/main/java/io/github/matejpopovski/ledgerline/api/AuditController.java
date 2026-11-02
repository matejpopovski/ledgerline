package io.github.matejpopovski.ledgerline.api;

import io.github.matejpopovski.ledgerline.audit.AuditReport;
import io.github.matejpopovski.ledgerline.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Audit")
class AuditController {

  private final AuditService audit;

  AuditController(AuditService audit) {
    this.audit = audit;
  }

  @GetMapping("/v1/audit")
  @Operation(summary = "Recompute balances from the journal and report any disagreement")
  AuditReport audit() {
    return audit.run();
  }
}
