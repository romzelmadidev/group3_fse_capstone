package com.bank.cbs.scheduler;

import com.bank.cbs.dto.CobExecutionResponseDto;
import com.bank.cbs.service.CbsCobBatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CbsCobSchedulerTest {

    @Mock
    private CbsCobBatchService cobBatchService;

    private CbsCobScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new CbsCobScheduler(cobBatchService);
    }

    @Test
    void testTriggerScheduledCob_InvokesBatchServiceSuccessfully() {
        CobExecutionResponseDto mockResponse = new CobExecutionResponseDto(
                LocalDate.of(2026, 10, 10),
                "ONLINE",
                true,
                Collections.emptyList()
        );
        when(cobBatchService.runCobBatch()).thenReturn(mockResponse);

        assertDoesNotThrow(() -> scheduler.triggerScheduledCob());

        verify(cobBatchService, times(1)).runCobBatch();
    }

    @Test
    void testTriggerScheduledCob_CatchesAndLogsExceptionGracefully() {
        when(cobBatchService.runCobBatch()).thenThrow(new RuntimeException("Simulated database outage during scheduled cutoff"));

        // Asserts no uncaught exception is thrown out of the scheduled worker
        assertDoesNotThrow(() -> scheduler.triggerScheduledCob());

        verify(cobBatchService, times(1)).runCobBatch();
    }
}
