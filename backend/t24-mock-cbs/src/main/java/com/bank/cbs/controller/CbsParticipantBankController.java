package com.bank.cbs.controller;

import com.bank.cbs.entity.master.ParticipantBankMaster;
import com.bank.cbs.repository.master.ParticipantBankRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller exposing participant banks directory endpoints.
 */
@RestController
@RequestMapping("/api/v1/cbs/interbank/banks")
public class CbsParticipantBankController {

    private final ParticipantBankRepository bankRepository;

    public CbsParticipantBankController(ParticipantBankRepository bankRepository) {
        this.bankRepository = bankRepository;
    }

    @GetMapping
    public ResponseEntity<List<ParticipantBankMaster>> getParticipantBanks() {
        return ResponseEntity.ok(bankRepository.findAll());
    }
}
