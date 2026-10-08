package com.udapay.ledger.service;

import com.udapay.ledger.dto.TransferRequest;
import com.udapay.ledger.model.Transfer;
import com.udapay.ledger.repository.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Business operations on the ledger. */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transferRepository;

    public TransferService(TransferRepository transferRepository) {
        this.transferRepository = transferRepository;
    }

    @Transactional
    public Transfer createTransfer(String username, TransferRequest request) {
        Transfer transfer = new Transfer(username, request.amount(), request.currency(), request.description());
        Transfer saved = transferRepository.save(transfer);
        log.info("Transfer created id={} username={} amount={} currency={}",
                saved.getId(), saved.getUsername(), saved.getAmount(), saved.getCurrency());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Transfer> listTransfers() {
        return transferRepository.findAllByOrderByTimestampDesc();
    }

    /**
     * Exercise 1: delegates to the parameterized repository query. The username is
     * bound as a value, so it can never alter the SQL that is executed.
     */
    @Transactional(readOnly = true)
    public List<Transfer> searchByUsername(String username) {
        log.info("Searching transfers for username='{}' (parameterized query)", username);
        List<Transfer> results = transferRepository.searchByUsername(username);
        log.info("Search for username='{}' returned {} transfer(s)", username, results.size());
        return results;
    }
}
