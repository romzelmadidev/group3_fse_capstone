package com.bank.ledger.notification.consumer;

import com.bank.ledger.contracts.dto.TransactionNotificationEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreBankingEventMappingTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void coreBankingTransferExecutedEventKeepsItsTransferAndAccounts() throws Exception {
        String json = """
                {"eventId":"e1","eventType":"TransferExecutedEvent","version":"1.0",
                 "transactionId":"TXN-1D78D709","cbsReference":"TXN-TXN-1D78D709",
                 "sourceAccountId":"1000-8801-0003","destinationAccountId":"1000-8801-0001",
                 "amount":1000.0000,"currency":"PHP","sourceBalanceAfter":9000.0000,
                 "destinationBalanceAfter":11000.0000,"executedAtUtc":1791787094.039}
                """;

        TransactionNotificationEvent event = mapper.readValue(json, TransactionNotificationEvent.class);

        assertThat(event.getTransferId()).isEqualTo("TXN-1D78D709");
        assertThat(event.getSourceAccount()).isEqualTo("1000-8801-0003");
        assertThat(event.getDestinationAccount()).isEqualTo("1000-8801-0001");
        assertThat(event.getAfterBalance()).isEqualByComparingTo("9000");
        assertThat(event.getEventType()).isEqualTo("TransferExecutedEvent");
        assertThat(event.getTimestamp()).isNotNull();
    }

    @Test
    void legacySnakeCaseEventStillMaps() throws Exception {
        String json = "{\"transfer_id\":\"T1\",\"source_account\":\"A\",\"destination_account\":\"B\",\"status\":\"COMMITTED\"}";

        TransactionNotificationEvent event = mapper.readValue(json, TransactionNotificationEvent.class);

        assertThat(event.getTransferId()).isEqualTo("T1");
        assertThat(event.getSourceAccount()).isEqualTo("A");
        assertThat(event.getDestinationAccount()).isEqualTo("B");
    }
}
