package com.bank.cbs.controller;

import com.bank.cbs.dto.CobExecutionResponseDto;
import com.bank.cbs.entity.master.SystemDateMaster;
import com.bank.cbs.repository.master.SystemDateMasterRepository;
import com.bank.cbs.service.CbsCobBatchService;
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

    @PostMapping("/cob/run")
    public ResponseEntity<CobExecutionResponseDto> runCob() {
        return ResponseEntity.ok(cobBatchService.runCobBatch());
    }

    @GetMapping("/system-date")
    public ResponseEntity<SystemDateMaster> getSystemDate() {
        SystemDateMaster sysDate = systemDateRepository.findTopByOrderBySystemDateIdAsc()
                .orElseGet(() -> {
                    SystemDateMaster d = new SystemDateMaster();
                    d.setSystemDateId("SYS-DATE-1");
                    d.setBusinessDate(LocalDate.now());
                    d.setStatus("ONLINE");
                    d.setPostingWindowOpen(true);
                    return d;
                });
        return ResponseEntity.ok(sysDate);
    }
}
