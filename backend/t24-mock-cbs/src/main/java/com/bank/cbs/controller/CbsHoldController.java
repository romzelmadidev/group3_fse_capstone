package com.bank.cbs.controller;

import com.bank.cbs.dto.HoldRequestDto;
import com.bank.cbs.dto.HoldResponseDto;
import com.bank.cbs.service.CbsHoldService;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/cbs/holds")
public class CbsHoldController {

    private final CbsHoldService holdService;

    public CbsHoldController(CbsHoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> placeHold(@RequestBody String ofsMessage) {
        String refId = "HLD-" + UUID.randomUUID();
        String accountId = null;
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            refId = fields.getOrDefault("LOCKED.EVENT.ID",
                    fields.getOrDefault("REFERENCE.ID", fields.getOrDefault("TXN.ID", refId)));
            accountId = fields.getOrDefault("ACCOUNT.NUMBER", fields.get("ACCOUNT.ID"));
            BigDecimal amount = fields.containsKey("AMOUNT") ? new BigDecimal(fields.get("AMOUNT")) : BigDecimal.ZERO;
            String currency = fields.getOrDefault("CURRENCY", "PHP");
            String reason = fields.getOrDefault("DESCRIPTION", fields.getOrDefault("REASON", "HOLD"));

            HoldRequestDto request = new HoldRequestDto(refId, accountId, amount, currency, reason);
            HoldResponseDto resp = holdService.placeHold(request);

            return ResponseEntity.ok(OfsMessageUtil.buildHoldResponse(
                    true, resp.referenceId(), resp.accountId(), resp.availableBalance(), resp.status(), resp.message()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildHoldResponse(
                    false, refId, accountId != null ? accountId : "UNKNOWN", BigDecimal.ZERO, "FAILED", e.getMessage()
            ));
        }
    }

    @PostMapping(value = "/release", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> releaseHold(@RequestBody String ofsMessage) {
        String refId = "REL-" + UUID.randomUUID();
        String accountId = null;
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            refId = fields.getOrDefault("LOCKED.EVENT.ID",
                    fields.getOrDefault("REFERENCE.ID", fields.getOrDefault("TXN.ID", refId)));
            accountId = fields.getOrDefault("ACCOUNT.NUMBER", fields.get("ACCOUNT.ID"));
            BigDecimal amount = fields.containsKey("AMOUNT") ? new BigDecimal(fields.get("AMOUNT")) : BigDecimal.ZERO;
            String currency = fields.getOrDefault("CURRENCY", "PHP");
            String reason = fields.getOrDefault("DESCRIPTION", fields.getOrDefault("REASON", "RELEASE"));

            HoldRequestDto request = new HoldRequestDto(refId, accountId, amount, currency, reason);
            HoldResponseDto resp = holdService.releaseHold(request);

            return ResponseEntity.ok(OfsMessageUtil.buildHoldResponse(
                    true, resp.referenceId(), resp.accountId(), resp.availableBalance(), resp.status(), resp.message()
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildHoldResponse(
                    false, refId, accountId != null ? accountId : "UNKNOWN", BigDecimal.ZERO, "FAILED", e.getMessage()
            ));
        }
    }
}
