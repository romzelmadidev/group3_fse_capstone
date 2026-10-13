package com.bank.cbs.controller;

import com.bank.cbs.entity.master.ParticipantBankMaster;
import com.bank.cbs.repository.master.ParticipantBankRepository;
import com.bank.ledger.contracts.dto.ParticipantBankDto;
import com.bank.ledger.contracts.ofs.OfsMessageUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CbsParticipantBankControllerTest {

    @Mock
    private ParticipantBankRepository participantBankRepository;

    @InjectMocks
    private CbsParticipantBankController controller;

    @Test
    @DisplayName("Participant banks directory is strictly returned as Temenos OFS text/plain protocol")
    void testGetParticipantBanks_ReturnsOfsPlainText() {
        ParticipantBankMaster coobs = ParticipantBankMaster.builder()
                .bankCode("COOBS")
                .bankName("Cooperative Overseas Banking System (CooBS)")
                .bicSwiftCode("COOBPHM2XXX")
                .clearingNetwork("BILATERAL")
                .nostroAccountId("ACC-NOSTRO-BANK-B")
                .apiEndpointUrl("http://transfer-orchestrator:8082/api/v1/mock-partner/transfers")
                .apiKey("COOBS-KEY-999")
                .isActive(1)
                .settlementCurrency("PHP")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ParticipantBankMaster aurabank = ParticipantBankMaster.builder()
                .bankCode("AURABANK")
                .bankName("AuraBank Core Retail")
                .bicSwiftCode("AURAPHM1XXX")
                .clearingNetwork("CORE")
                .nostroAccountId("ACC-NOSTRO-BANK-B")
                .apiEndpointUrl("")
                .apiKey("AURABANK-SECURE-KEY-2026")
                .isActive(1)
                .settlementCurrency("PHP")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(participantBankRepository.findAll()).thenReturn(List.of(coobs, aurabank));

        ResponseEntity<String> response = controller.getParticipantBanks();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertTrue(response.getHeaders().getContentType().includes(MediaType.TEXT_PLAIN), "Must produce text/plain");

        String body = response.getBody();
        assertNotNull(body);
        assertTrue(body.startsWith("//1,SUCCESS,OPERATION=PARTICIPANT.BANKS"));
        assertTrue(body.contains("COUNT=2"));
        assertTrue(body.contains("COOBS"));
        assertTrue(body.contains("AURABANK"));

        List<ParticipantBankDto> parsed = OfsMessageUtil.parseParticipantBanksResponse(body);
        assertEquals(2, parsed.size());
        assertEquals("COOBS", parsed.get(0).getBankCode());
        assertEquals("Cooperative Overseas Banking System (CooBS)", parsed.get(0).getBankName());
        assertEquals("ACC-NOSTRO-BANK-B", parsed.get(0).getNostroAccountId());
        assertEquals("AURABANK", parsed.get(1).getBankCode());
    }
}
