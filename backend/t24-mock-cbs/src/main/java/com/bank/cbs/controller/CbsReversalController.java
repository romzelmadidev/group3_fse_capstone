package com.bank.cbs.controller;

import com.bank.cbs.dto.ReversalActionDto;
import com.bank.cbs.dto.ReversalRequestDto;
import com.bank.cbs.entity.master.ReversalRequestMaster;
import com.bank.cbs.service.CbsReversalService;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/cbs/reversals")
public class CbsReversalController {

    private final CbsReversalService reversalService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CbsReversalController(CbsReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping(value = "/request", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> requestReversal(@RequestBody String ofsMessage) {
        String originalTxId = "UNKNOWN";
        try {
            ReversalRequestDto dto;
            if (ofsMessage.trim().startsWith("{")) {
                dto = objectMapper.readValue(ofsMessage, ReversalRequestDto.class);
            } else {
                Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
                originalTxId = fields.getOrDefault("ORIGINAL.FT.NO",
                        fields.getOrDefault("ORIGINAL.TX.ID", fields.getOrDefault("TRANSACTION.ID", "UNKNOWN")));
                String makerId = fields.getOrDefault("MAKER", fields.getOrDefault("MAKER.ID", "MAKER01"));
                String reason = fields.getOrDefault("REASON", fields.getOrDefault("DISPUTE.REASON", "DISPUTE"));
                String notes = fields.getOrDefault("NOTES", fields.getOrDefault("MAKER.NOTES", reason));
                dto = new ReversalRequestDto(originalTxId, makerId, reason, notes);
            }
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

    @PostMapping(value = "/approve", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> approveReversal(@RequestBody String ofsMessage) {
        String ticketId = "UNKNOWN";
        try {
            ReversalActionDto action;
            if (ofsMessage.trim().startsWith("{")) {
                action = objectMapper.readValue(ofsMessage, ReversalActionDto.class);
            } else {
                Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
                ticketId = fields.getOrDefault("TICKET.ID", fields.getOrDefault("REVERSAL.REQUEST.ID", "UNKNOWN"));
                String checkerId = fields.getOrDefault("CHECKER", fields.getOrDefault("CHECKER.ID", "MGR02"));
                String notes = fields.getOrDefault("NOTES", fields.getOrDefault("CHECKER.NOTES", "Approved by checker"));
                action = new ReversalActionDto(ticketId, checkerId, null, notes);
            }
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

    @PostMapping(value = "/reject", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> rejectReversal(@RequestBody String ofsMessage) {
        String ticketId = "UNKNOWN";
        try {
            ReversalActionDto action;
            if (ofsMessage.trim().startsWith("{")) {
                action = objectMapper.readValue(ofsMessage, ReversalActionDto.class);
            } else {
                Map<String, String> fields = OfsMessageUtil.parseOfsFields(ofsMessage);
                ticketId = fields.getOrDefault("TICKET.ID", fields.getOrDefault("REVERSAL.REQUEST.ID", "UNKNOWN"));
                String checkerId = fields.getOrDefault("CHECKER", fields.getOrDefault("CHECKER.ID", "MGR02"));
                String reason = fields.getOrDefault("REASON", fields.getOrDefault("REJECTION.REASON", "Rejected by checker"));
                String notes = fields.getOrDefault("NOTES", fields.getOrDefault("CHECKER.NOTES", reason));
                action = new ReversalActionDto(ticketId, checkerId, reason, notes);
            }
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
