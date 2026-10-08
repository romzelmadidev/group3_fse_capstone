package com.bank.cbs.controller;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.entity.master.ReversalRequestMaster;
import com.bank.cbs.service.CbsReversalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cbs/reversals")
public class CbsReversalController {

    private final CbsReversalService reversalService;

    public CbsReversalController(CbsReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping("/request")
    public ResponseEntity<ReversalRequestMaster> requestReversal(@RequestBody ReversalRequestDto dto) {
        ReversalRequestMaster result = reversalService.requestReversal(dto);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/approve")
    public ResponseEntity<ReversalRequestMaster> approveReversal(@RequestBody ReversalActionDto action) {
        ReversalRequestMaster result = reversalService.approveReversal(action);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reject")
    public ResponseEntity<ReversalRequestMaster> rejectReversal(@RequestBody ReversalActionDto action) {
        ReversalRequestMaster result = reversalService.rejectReversal(action);
        return ResponseEntity.ok(result);
    }
}
