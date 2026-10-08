package com.udapay.ledger.service;

import com.udapay.ledger.dto.TransferRequest;
import com.udapay.ledger.model.Transfer;
import com.udapay.ledger.repository.TransferRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository repository;

    @Test
    @DisplayName("searchByUsername delegates to the parameterized repository method with the raw input")
    void searchDelegatesToParameterizedQuery() {
        TransferService service = new TransferService(repository);
        String payload = "admin' OR '1'='1";
        when(repository.searchByUsername(payload)).thenReturn(List.of());

        List<Transfer> result = service.searchByUsername(payload);

        assertThat(result).isEmpty();
        verify(repository).searchByUsername(payload);      // passed through untouched, bound as a value
        verify(repository, never()).findAll();
    }

    @Test
    @DisplayName("createTransfer stamps the caller's username onto the entity")
    void createUsesAuthenticatedUsername() {
        TransferService service = new TransferService(repository);
        when(repository.save(any(Transfer.class))).thenAnswer(inv -> inv.getArgument(0));

        Transfer created = service.createTransfer("alice",
                new TransferRequest(new BigDecimal("100.50"), "eur", "rent"));

        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
        assertThat(created.getAmount()).isEqualByComparingTo("100.50");
        assertThat(created.getCurrency()).isEqualTo("EUR");
        assertThat(created.getDescription()).isEqualTo("rent");
    }
}
