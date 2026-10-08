package com.udapay.ledger.dto;

import com.udapay.ledger.model.Transfer;
import com.udapay.ledger.model.TransferStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** Outbound representation of a {@link Transfer}. */
public record TransferResponse(
        Long id,
        String username,
        BigDecimal amount,
        String currency,
        String description,
        TransferStatus status,
        Instant timestamp
) {
    public static TransferResponse from(Transfer t) {
        return new TransferResponse(
                t.getId(), t.getUsername(), t.getAmount(), t.getCurrency(),
                t.getDescription(), t.getStatus(), t.getTimestamp());
    }
}
