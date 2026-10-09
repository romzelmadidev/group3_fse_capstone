package com.bank.cbs.scheduler;

import com.bank.cbs.dto.CobExecutionResponseDto;
import com.bank.cbs.service.CbsCobBatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Automated Close of Business (COB) batch scheduler.
 * Executes nightly batch accounting, ADB fee assessment, daily interest accrual,
 * GL reconciliation, balance snapshots, and business date rollover.
 *
 * Configured by default to 21:00:00 (9:00 PM) in the bank's timezone (Asia/Manila),
 * matching real-world banking end-of-day operational cutoff windows.
 */
@Component
@ConditionalOnProperty(name = "cbs.cob.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CbsCobScheduler {

    private static final Logger log = LoggerFactory.getLogger(CbsCobScheduler.class);

    private final CbsCobBatchService cobBatchService;

    public CbsCobScheduler(CbsCobBatchService cobBatchService) {
        this.cobBatchService = cobBatchService;
    }

    @Scheduled(cron = "${cbs.cob.scheduler.cron:0 0 21 * * *}", zone = "${cbs.cob.scheduler.zone:Asia/Manila}")
    public void triggerScheduledCob() {
        log.info("Triggering scheduled Close of Business (COB) batch execution at configured cutoff window...");
        try {
            CobExecutionResponseDto response = cobBatchService.runCobBatch();
            log.info("Scheduled Close of Business (COB) completed successfully. New business date: {}, status: {}",
                    response.businessDate(), response.status());
        } catch (Exception e) {
            log.error("Scheduled Close of Business (COB) encountered an error during execution: {}", e.getMessage(), e);
        }
    }
}
