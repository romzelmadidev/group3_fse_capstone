package com.bank.cbs.controller;

import com.bank.cbs.dto.CobExecutionResponseDto;
import com.bank.cbs.entity.master.SystemDateMaster;
import com.bank.cbs.repository.master.SystemDateMasterRepository;
import com.bank.cbs.service.CbsCobBatchService;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1/cbs")
public class CobController {

    private final CbsCobBatchService cobBatchService;
    private final SystemDateMasterRepository systemDateRepository;

    public CobController(CbsCobBatchService cobBatchService, SystemDateMasterRepository systemDateRepository) {
        this.cobBatchService = cobBatchService;
        this.systemDateRepository = systemDateRepository;
    }

    @PostMapping(value = "/cob/run", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> runCob() {
        try {
            CobExecutionResponseDto resp = cobBatchService.runCobBatch();
            return ResponseEntity.ok(OfsMessageUtil.buildCobRunResponse(
                    true, resp.batchId(), resp.accountsProcessed(), resp.totalFeesCollected(), resp.totalInterestAccrued(), resp.status(), "COB batch execution completed"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildCobRunResponse(
                    false, "ERR", 0, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "FAILED", e.getMessage()
            ));
        }
    }

    @GetMapping(value = "/system-date", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getSystemDate() {
        SystemDateMaster sysDate = systemDateRepository.findTopByOrderBySystemDateIdAsc()
                .orElseGet(() -> {
                    SystemDateMaster d = new SystemDateMaster();
                    d.setSystemDateId("SYS-DATE-1");
                    d.setBusinessDate(LocalDate.now());
                    d.setStatus("ONLINE");
                    d.setPostingWindowOpen(true);
                    return d;
                });
        return ResponseEntity.ok(OfsMessageUtil.buildSystemDateResponse(
                sysDate.getSystemDateId(),
                sysDate.getBusinessDate().toString(),
                sysDate.getStatus(),
                sysDate.isPostingWindowOpen()
        ));
    }
}
