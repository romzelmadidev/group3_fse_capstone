package com.bank.cbs.controller;

import com.bank.cbs.dto.TransferRequestDto;
import com.bank.cbs.dto.TransferResponseDto;
import com.bank.cbs.service.CbsFundsTransferService;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cbs")
public class CbsPostingController {

    private final CbsFundsTransferService transferService;
    private final CbsReversalService reversalService;

    public CbsPostingController(CbsFundsTransferService transferService, CbsReversalService reversalService) {
        this.transferService = transferService;
        this.reversalService = reversalService;
    }

    @PostMapping("/postings/transfer")
    public ResponseEntity<TransferResponseDto> postTransfer(@RequestBody TransferRequestDto request) {
        TransferResponseDto response = transferService.executeTransfer(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/ofs", consumes = "text/plain", produces = "text/plain")
    public ResponseEntity<String> executeOfs(@RequestBody String ofsMessage) {
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            String operation = fields.get("OPERATION");

            if ("FUNDS.TRANSFER,INITIATE".equalsIgnoreCase(operation)) {
                String txId = fields.containsKey("TXN.ID") ? fields.get("TXN.ID") : UUID.randomUUID().toString();
                String debitAcct = fields.get("DEBIT.ACCT.NO");
                String creditAcct = fields.get("CREDIT.ACCT.NO");
                BigDecimal amount = new BigDecimal(fields.get("AMOUNT"));
                String currency = fields.getOrDefault("CURRENCY", "PHP");

                TransferRequestDto req = new TransferRequestDto(
                        txId,
                        debitAcct,
                        creditAcct,
                        amount,
                        currency,
                        "OFS Transaction",
                        "OFS_GW",
                        txId
                );

                TransferResponseDto resp = transferService.executeTransfer(req);
                return ResponseEntity.ok(resp.ofsResponse());
            }

            return ResponseEntity.badRequest().body("//-1,FAILURE,ERROR=UNSUPPORTED_OPERATION");
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "ERROR", e.getMessage()));
        }
    }
}
