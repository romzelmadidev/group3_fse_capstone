package com.bank.cbs.controller;

import com.bank.cbs.dto.HoldRequestDto;
import com.bank.cbs.dto.HoldResponseDto;
import com.bank.cbs.service.CbsHoldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cbs/holds")
public class CbsHoldController {

    private final CbsHoldService holdService;

    public CbsHoldController(CbsHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    public ResponseEntity<HoldResponseDto> placeHold(@RequestBody HoldRequestDto request) {
        return ResponseEntity.ok(holdService.placeHold(request));
    }

    @PostMapping("/release")
    public ResponseEntity<HoldResponseDto> releaseHold(@RequestBody HoldRequestDto request) {
        return ResponseEntity.ok(holdService.releaseHold(request));
    }
}
