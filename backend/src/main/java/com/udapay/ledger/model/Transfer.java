package com.udapay.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single ledger transfer. The schema is owned by Flyway
 * ({@code db/migration/V1__initial_schema.sql}); Hibernate only validates it.
 */
@Entity
@Table(name = "transfers")
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Keycloak {@code preferred_username} of the account owner. */
    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "USD";

    @Column(length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransferStatus status = TransferStatus.COMPLETED;

    /** When the transfer was recorded (UTC). Mapped to {@code created_at}. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant timestamp;

    protected Transfer() {
        // JPA
    }

    public Transfer(String username, BigDecimal amount, String currency, String description) {
        this.username = username;
        this.amount = amount;
        if (currency != null && !currency.isBlank()) {
            this.currency = currency.toUpperCase();
        }
        this.description = description;
    }

    @PrePersist
    void onCreate() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setStatus(TransferStatus status) {
        this.status = status;
    }

    /** Test / fixture helper: allow an explicit timestamp for deterministic ordering. */
    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
