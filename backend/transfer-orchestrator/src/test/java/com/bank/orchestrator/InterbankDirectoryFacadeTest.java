package com.bank.orchestrator;

import com.bank.ledger.contracts.dto.ParticipantBankDto;
import com.bank.orchestrator.controller.InterbankDirectoryController;
import com.bank.orchestrator.service.BankRoutingService;
import com.bank.orchestrator.service.CbsClientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterbankDirectoryFacadeTest {

    @Mock
    private CbsClientService cbsClientService;

    @Test
    @DisplayName("Orchestrator Facade queries CBS OFS and exposes participant banks as REST/JSON")
    void testDirectoryFacade_QueriesCbsAndExposesJson() {
        ParticipantBankDto cbsCoobs = ParticipantBankDto.builder()
                .bankCode("COOBS")
                .bankName("Cooperative Overseas Banking System (CooBS)")
                .bicSwiftCode("COOBPHM2XXX")
                .clearingNetwork("BILATERAL")
                .nostroAccountId("ACC-NOSTRO-BANK-B")
                .apiEndpointUrl("http://localhost:8082/api/v1/mock-partner/transfers")
                .isActive(1)
                .settlementCurrency("PHP")
                .build();

        ParticipantBankDto cbsAura = ParticipantBankDto.builder()
                .bankCode("AURABANK")
                .bankName("AuraBank Core Retail")
                .bicSwiftCode("AURAPHM1XXX")
                .clearingNetwork("CORE")
                .nostroAccountId("ACC-NOSTRO-BANK-B")
                .apiEndpointUrl("")
                .isActive(1)
                .settlementCurrency("PHP")
                .build();

        when(cbsClientService.getParticipantBanks()).thenReturn(List.of(cbsCoobs, cbsAura));

        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("PARTNER_BANK_URL", "http://custom-coobs-partner:9090/wire");

        BankRoutingService routingService = new BankRoutingService(
                environment,
                cbsClientService,
                "http://localhost:8082/api/v1/mock-partner/transfers"
        );

        InterbankDirectoryController controller = new InterbankDirectoryController(routingService);

        ResponseEntity<List<BankRoutingService.BankInfo>> response = controller.getActiveParticipantBanks();

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        List<BankRoutingService.BankInfo> banks = response.getBody();
        assertNotNull(banks);
        assertEquals(2, banks.size());

        BankRoutingService.BankInfo coobs = banks.stream()
                .filter(b -> "COOBS".equals(b.bankCode()))
                .findFirst()
                .orElse(null);
        assertNotNull(coobs);
        assertEquals("Cooperative Overseas Banking System (CooBS)", coobs.bankName());
        // Verify Tier 1 env override took precedence over default/database URL
        assertEquals("http://custom-coobs-partner:9090/wire", coobs.apiEndpointUrl());

        verify(cbsClientService, atLeastOnce()).getParticipantBanks();
    }
}
