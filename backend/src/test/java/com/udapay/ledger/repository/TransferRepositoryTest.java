package com.udapay.ledger.repository;

import com.udapay.ledger.model.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercise 1 — proves the parameterized JPQL query cannot be subverted.
 * Runs the real Flyway migration against H2 (PostgreSQL mode).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TransferRepositoryTest {

    private static final String INJECTION = "admin' OR '1'='1";

    @Autowired
    private TransferRepository repository;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.save(withTimestamp(new Transfer("admin", new BigDecimal("10.00"), "USD", "older"),
                Instant.parse("2026-01-01T10:00:00Z")));
        repository.save(withTimestamp(new Transfer("admin", new BigDecimal("20.00"), "USD", "newer"),
                Instant.parse("2026-01-02T10:00:00Z")));
        repository.save(withTimestamp(new Transfer("alice", new BigDecimal("30.00"), "EUR", "alice"),
                Instant.parse("2026-01-03T10:00:00Z")));
        repository.save(withTimestamp(new Transfer("bob", new BigDecimal("40.00"), "USD", "bob"),
                Instant.parse("2026-01-04T10:00:00Z")));
    }

    @Test
    @DisplayName("classic injection payload returns nothing: the input is bound as a value, not SQL")
    void injectionPayloadIsTreatedAsPlainText() {
        assertThat(repository.count()).isEqualTo(4);

        List<Transfer> result = repository.searchByUsername(INJECTION);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("only a row whose username literally equals the payload is returned")
    void literalMatchStillWorks() {
        repository.save(new Transfer(INJECTION, new BigDecimal("1.00"), "USD", "weird-but-legal"));

        List<Transfer> result = repository.searchByUsername(INJECTION);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getUsername()).isEqualTo(INJECTION);
    }

    @Test
    @DisplayName("other injection shapes (comments, UNION, stacked statements) are inert too")
    void otherPayloadsAreInert() {
        assertThat(repository.searchByUsername("admin'--")).isEmpty();
        assertThat(repository.searchByUsername("admin' UNION SELECT * FROM transfers --")).isEmpty();
        assertThat(repository.searchByUsername("x'; DROP TABLE transfers; --")).isEmpty();
        assertThat(repository.searchByUsername("%")).isEmpty();
        assertThat(repository.count()).isEqualTo(4);   // table still intact
    }

    @Test
    @DisplayName("exact username match, newest first")
    void exactMatchOrderedByTimestampDesc() {
        List<Transfer> result = repository.searchByUsername("admin");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Transfer::getDescription).containsExactly("newer", "older");
        assertThat(result).allMatch(t -> t.getUsername().equals("admin"));
    }

    @Test
    @DisplayName("lookup is case-sensitive and whitespace-sensitive")
    void matchIsExact() {
        assertThat(repository.searchByUsername("Admin")).isEmpty();
        assertThat(repository.searchByUsername("admin ")).isEmpty();
    }

    private static Transfer withTimestamp(Transfer t, Instant ts) {
        t.setTimestamp(ts);
        return t;
    }
}
