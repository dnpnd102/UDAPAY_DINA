package com.udapay.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Udapay Ledger Service entry point.
 *
 * <p>Secrets are loaded from HashiCorp Vault during the bootstrap phase
 * (see {@code bootstrap.yml}); security is configured in
 * {@link com.udapay.ledger.config.SecurityConfig}; every request is tagged
 * with a W3C trace context by
 * {@link com.udapay.ledger.filter.CorrelationIdFilter}.
 */
@SpringBootApplication
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
