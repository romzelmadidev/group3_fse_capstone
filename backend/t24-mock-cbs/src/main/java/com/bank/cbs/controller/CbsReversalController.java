package com.bank.cbs.controller;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.entity.master.ReversalRequestMaster;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.dto.ReversalTicketDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dedicated Dual-Control Reversal Workflow Controller.
 * Exposes teller maker-checker four-eyes approval endpoints strictly using OFS syntax:
 * 1. Query Reversal Requests (GET /api/v1/cbs/reversals)
 * 2. Request Reversal (POST /api/v1/cbs/reversals/request)
 * 3. Approve Reversal (POST /api/v1/cbs/reversals/approve)
 * 4. Reject Reversal (POST /api/v1/cbs/reversals/reject)
 */
@RestController
@RequestMapping("/api/v1/cbs/reversals")
public class CbsReversalController {

    private final CbsReversalService reversalService;

    public CbsReversalController(CbsReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> getReversalRequests(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        List<ReversalRequestMaster> requests = reversalService.getReversalRequests(status, page, size);
        List<ReversalTicketDto> dtos = requests.stream()
                .map(r -> ReversalTicketDto.builder()
                        .ticketId(r.getTicketId())
                        .originalTransactionId(r.getOriginalTxId())
                        .makerId(r.getMakerId())
                        .checkerId(r.getCheckerId())
                        .status(r.getStatus())
                        .disputeReason(r.getDisputeReason())
                        .makerNotes(r.getMakerNotes())
                        .checkerNotes(r.getCheckerNotes())
                        .reversalTransactionId(r.getReversalTxId())
                        .createdAt(r.getCreatedAt())
                        .resolvedAt(r.getResolvedAt())
                        .build())
                .toList();
        String ofs = OfsMessageUtil.buildReversalListResponse(dtos, page, size);
        return ResponseEntity.ok(ofs);
    }

    @PostMapping(value = "/request", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> requestReversal(@RequestBody String ofsMessage) {
        if (ofsMessage == null || ofsMessage.trim().isEmpty() || ofsMessage.trim().startsWith("{")) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "INVALID_FORMAT", "Payload must be plain-text Temenos OFS syntax"));
        }

        String originalTxId = "UNKNOWN";
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            originalTxId = fields.getOrDefault("ORIGINAL.FT.NO",
                    fields.getOrDefault("ORIGINAL.TX.ID", fields.getOrDefault("TRANSACTION.ID", "UNKNOWN")));
            String makerId = fields.get("MAKER");
            if (makerId == null || makerId.isBlank()) {
                makerId = fields.get("MAKER.ID");
            }
            String reason = fields.getOrDefault("REASON", fields.getOrDefault("DISPUTE.REASON", "DISPUTE"));
            String notes = fields.getOrDefault("NOTES", fields.getOrDefault("MAKER.NOTES", reason));

            ReversalRequestDto dto = new ReversalRequestDto(originalTxId, makerId, reason, notes);
            ReversalRequestMaster result = reversalService.requestReversal(dto);

            return ResponseEntity.ok(OfsMessageUtil.buildReversalResponseMessage(
                    true, result.getTicketId(), result.getStatus(), result.getOriginalTxId(), result.getReversalTxId(), "Reversal request registered"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildReversalResponseMessage(
                    false, "ERR", "FAILED", originalTxId, null, e.getMessage()
            ));
        }
    }

    @PostMapping(value = "/approve", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> approveReversal(@RequestBody String ofsMessage) {
        if (ofsMessage == null || ofsMessage.trim().isEmpty() || ofsMessage.trim().startsWith("{")) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "INVALID_FORMAT", "Payload must be plain-text Temenos OFS syntax"));
        }

        String ticketId = "UNKNOWN";
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            ticketId = fields.getOrDefault("TICKET.ID", fields.getOrDefault("REVERSAL.REQUEST.ID", "UNKNOWN"));
            String checkerId = fields.get("CHECKER");
            if (checkerId == null || checkerId.isBlank()) {
                checkerId = fields.get("CHECKER.ID");
            }
            String notes = fields.getOrDefault("NOTES", fields.getOrDefault("CHECKER.NOTES", "Approved by checker"));

            ReversalActionDto action = new ReversalActionDto(ticketId, checkerId, null, notes);
            ReversalRequestMaster result = reversalService.approveReversal(action);

            return ResponseEntity.ok(OfsMessageUtil.buildReversalResponseMessage(
                    true, result.getTicketId(), result.getStatus(), result.getOriginalTxId(), result.getReversalTxId(), "Reversal executed successfully"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildReversalResponseMessage(
                    false, ticketId, "FAILED", null, null, e.getMessage()
            ));
        }
    }

    @PostMapping(value = "/reject", consumes = MediaType.TEXT_PLAIN_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> rejectReversal(@RequestBody String ofsMessage) {
        if (ofsMessage == null || ofsMessage.trim().isEmpty() || ofsMessage.trim().startsWith("{")) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildOfsResponse(false, "INVALID_FORMAT", "Payload must be plain-text Temenos OFS syntax"));
        }

        String ticketId = "UNKNOWN";
        try {
            Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
            ticketId = fields.getOrDefault("TICKET.ID", fields.getOrDefault("REVERSAL.REQUEST.ID", "UNKNOWN"));
            String checkerId = fields.get("CHECKER");
            if (checkerId == null || checkerId.isBlank()) {
                checkerId = fields.get("CHECKER.ID");
            }
            String reason = fields.getOrDefault("REASON", fields.getOrDefault("REJECTION.REASON", "Rejected by checker"));
            String notes = fields.getOrDefault("NOTES", fields.getOrDefault("CHECKER.NOTES", reason));

            ReversalActionDto action = new ReversalActionDto(ticketId, checkerId, reason, notes);
            ReversalRequestMaster result = reversalService.rejectReversal(action);

            return ResponseEntity.ok(OfsMessageUtil.buildReversalResponseMessage(
                    true, result.getTicketId(), result.getStatus(), result.getOriginalTxId(), result.getReversalTxId(), "Reversal rejected"
            ));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(OfsMessageUtil.buildReversalResponseMessage(
                    false, ticketId, "FAILED", null, null, e.getMessage()
            ));
        }
    }
}
