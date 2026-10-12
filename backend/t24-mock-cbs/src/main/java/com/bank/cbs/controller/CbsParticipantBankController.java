package com.bank.cbs.controller;

import com.bank.cbs.entity.master.ParticipantBankMaster;
import com.bank.cbs.repository.master.ParticipantBankRepository;
import com.bank.ledger.contracts.dto.ParticipantBankDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Core Banking System Controller exposing participant banks directory.
 * ARCHITECTURAL CONSTRAINT:
 * As a Core Banking System component, this controller NEVER exposes direct REST/JSON to outside channels.
 * It strictly communicates using the Temenos Open Financial Services (OFS) protocol (text/plain).
 * External clients (Mobile app, Web frontend, API gateway) must query the directory through the
 * enterprise Transfer Orchestrator facade (GET /api/v1/interbank/banks).
 */
@RestController
@RequestMapping("/api/v1/cbs/interbank/banks")
public class CbsParticipantBankController {

    private final ParticipantBankRepository bankRepository;

    public CbsParticipantBankController(ParticipantBankRepository bankRepository) {
        this.bankRepository = bankRepository;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getParticipantBanks() {
        List<ParticipantBankMaster> entities = bankRepository.findAll();
        List<ParticipantBankDto> dtos = entities.stream().map(e -> ParticipantBankDto.builder()
                .bankCode(e.getBankCode())
                .bankName(e.getBankName())
                .bicSwiftCode(e.getBicSwiftCode())
                .clearingNetwork(e.getClearingNetwork())
                .nostroAccountId(e.getNostroAccountId())
                .apiEndpointUrl(e.getApiEndpointUrl())
                .apiKey(e.getApiKey())
                .isActive(e.getIsActive() != null ? e.getIsActive() : 1)
                .settlementCurrency(e.getSettlementCurrency() != null ? e.getSettlementCurrency() : "PHP")
                .build()
        ).toList();

        String ofsResponse = OfsMessageUtil.buildParticipantBanksResponse(dtos);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(ofsResponse);
    }
}
