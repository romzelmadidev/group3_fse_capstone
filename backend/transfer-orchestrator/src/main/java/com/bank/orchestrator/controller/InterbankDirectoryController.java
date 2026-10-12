package com.bank.orchestrator.controller;

import com.bank.orchestrator.service.BankRoutingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Exposes Participant Banks Directory to API Gateway, UI, and external partners.
 */
@RestController
@RequestMapping("/api/v1/interbank/banks")
public class InterbankDirectoryController {

    private final BankRoutingService bankRoutingService;

    public InterbankDirectoryController(BankRoutingService bankRoutingService) {
        this.bankRoutingService = bankRoutingService;
    }

    @GetMapping
    public ResponseEntity<List<BankRoutingService.BankInfo>> getActiveParticipantBanks() {
        return ResponseEntity.ok(bankRoutingService.getParticipantBanks());
    }
}
