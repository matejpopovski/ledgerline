package io.github.matejpopovski.ledgerline;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Double-entry ledger service. */
@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(
    info =
        @Info(
            title = "ledgerline",
            version = "0.1.0",
            description =
                "Double-entry ledger. All amounts are integer minor units of the entry currency."))
public class LedgerlineApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerlineApplication.class, args);
  }
}
