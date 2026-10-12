package com.bank.orchestrator.controller;

import com.bank.orchestrator.service.BankRoutingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Enterprise Facade Controller for the Participant Banks Directory.
 * ARCHITECTURAL RESPONSIBILITY:
 * Under core banking segregation standards, T24 Core Banking System (CBS) never exposes its
 * internal participant bank tables directly to external channels or via raw REST/JSON.
 * CBS only speaks Temenos Open Financial Services (OFS) protocol (text/plain).
 * 
 * This controller serves as the canonical enterprise facade:
 * 1. Queries the T24 CBS OFS directory endpoint via CbsClientService.
 * 2. Parses the Temenos OFS syntax into validated domain models.
 * 3. Applies dynamic environment variable overrides (e.g. PARTNER_BANK_URL).
 * 4. Exposes standard REST/JSON to outside channels (Mobile App, Retail Web, API Gateway).
 */
@RestController
@RequestMapping("/api/v1/interbank/banks")
public class InterbankDirectoryController {

    private final BankRoutingService bankRoutingService;

    public InterbankDirectoryController(BankRoutingService bankRoutingService) {
        this.bankRoutingService = bankRoutingService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<BankRoutingService.BankInfo>> getActiveParticipantBanks() {
        return ResponseEntity.ok(bankRoutingService.getParticipantBanks());
    }
}
