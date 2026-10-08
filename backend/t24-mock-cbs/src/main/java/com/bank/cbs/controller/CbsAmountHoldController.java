package com.bank.cbs.controller;

import com.bank.cbs.entity.master.AmountHoldMaster;
import com.bank.cbs.service.CbsAmountHoldService;
import com.bank.ledger.contracts.dto.AmountHoldReleaseResponseDto;
import com.bank.ledger.contracts.dto.AmountHoldRequestDto;
import com.bank.ledger.contracts.dto.AmountHoldResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cbs/holds")
public class CbsAmountHoldController {

    private final CbsAmountHoldService holdService;

    public CbsAmountHoldController(CbsAmountHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    public ResponseEntity<AmountHoldResponseDto> placeHold(@Valid @RequestBody AmountHoldRequestDto request) {
        AmountHoldResponseDto response = holdService.createHold(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{holdId}")
    public ResponseEntity<AmountHoldReleaseResponseDto> deleteHold(@PathVariable String holdId) {
        AmountHoldReleaseResponseDto response = holdService.releaseHold(holdId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{holdId}/release")
    public ResponseEntity<AmountHoldReleaseResponseDto> releaseHold(@PathVariable String holdId) {
        AmountHoldReleaseResponseDto response = holdService.releaseHold(holdId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{holdId}/capture")
    public ResponseEntity<com.bank.ledger.contracts.dto.AmountHoldCaptureResponseDto> captureHold(
            @PathVariable String holdId,
            @RequestBody(required = false) com.bank.ledger.contracts.dto.AmountHoldCaptureRequestDto request) {
        if (request == null) {
            request = new com.bank.ledger.contracts.dto.AmountHoldCaptureRequestDto();
        }
        request.setHoldId(holdId);
        com.bank.ledger.contracts.dto.AmountHoldCaptureResponseDto response = holdService.captureHold(holdId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{holdId}")
    public ResponseEntity<AmountHoldMaster> getHold(@PathVariable String holdId) {
        return ResponseEntity.ok(holdService.getHoldById(holdId));
    }

    @GetMapping("/account/{accountId}")
    public ResponseEntity<List<AmountHoldMaster>> getHoldsByAccount(@PathVariable String accountId) {
        return ResponseEntity.ok(holdService.getHoldsByAccount(accountId));
    }
}
