package com.udapay.ledger.repository;

import com.udapay.ledger.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistence for {@link Transfer}.
 *
 * <p><strong>Exercise 1 — SQL injection.</strong> The original scaffold built a
 * native SQL string by concatenating the caller-supplied username
 * ({@code "... WHERE username = '" + username + "'"}). An input such as
 * {@code admin' OR '1'='1} then changed the <em>structure</em> of the statement
 * and returned every row in the table.
 *
 * <p>{@link #searchByUsername(String)} replaces it with a JPQL query that uses a
 * named parameter ({@code :username}). The driver sends the value separately
 * from the statement text, so the database treats it strictly as data. The
 * same payload now matches only a row whose username is literally
 * {@code admin' OR '1'='1}, i.e. none.
 */
public interface TransferRepository extends JpaRepository<Transfer, Long> {

    /**
     * Secure, parameterized lookup. JPQL (not {@code nativeQuery = true}) with a
     * bound {@code :username} parameter — the input can never be interpreted as SQL.
     */
    @Query("SELECT t FROM Transfer t WHERE t.username = :username ORDER BY t.timestamp DESC")
    List<Transfer> searchByUsername(@Param("username") String username);

    /** Newest-first listing used by {@code GET /api/v1/transfers}. */
    List<Transfer> findAllByOrderByTimestampDesc();
}
