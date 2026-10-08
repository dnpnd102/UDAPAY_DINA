package com.udapay.ledger.controller;

import com.udapay.ledger.dto.TransferRequest;
import com.udapay.ledger.dto.TransferResponse;
import com.udapay.ledger.model.Transfer;
import com.udapay.ledger.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * REST surface of the ledger. Every endpoint under {@code /api/v1/transfers}
 * requires a valid Keycloak JWT (see {@code SecurityConfig}); individual
 * operations are further restricted by realm role via {@link PreAuthorize}.
 */
@RestController
@RequestMapping(path = "/api/v1/transfers")
@Validated
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /** Any authenticated principal may list transfers. */
    @GetMapping
    public List<TransferResponse> listTransfers() {
        return transferService.listTransfers().stream().map(TransferResponse::from).toList();
    }

    /** Only principals holding the Keycloak realm role {@code writer} may post a transfer. */
    @PostMapping
    @PreAuthorize("hasRole('writer')")
    public ResponseEntity<TransferResponse> createTransfer(@Valid @RequestBody TransferRequest request,
                                                           @AuthenticationPrincipal Jwt jwt) {
        String username = resolveUsername(jwt);
        Transfer created = transferService.createTransfer(username, request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(TransferResponse.from(created));
    }

    /** Only principals holding the Keycloak realm role {@code reader} may search. */
    @GetMapping("/search")
    @PreAuthorize("hasRole('reader')")
    public List<TransferResponse> searchTransfers(
            @RequestParam("username")
            @NotBlank(message = "username must not be blank")
            @Size(max = 100, message = "username must be at most 100 characters")
            String username) {
        return transferService.searchByUsername(username).stream().map(TransferResponse::from).toList();
    }

    /** The ledger owner is the Keycloak {@code preferred_username}; fall back to {@code sub}. */
    private static String resolveUsername(Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No JWT principal present");
        }
        String preferred = jwt.getClaimAsString("preferred_username");
        return (preferred != null && !preferred.isBlank()) ? preferred : jwt.getSubject();
    }
}
