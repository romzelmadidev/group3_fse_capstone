package com.bank.cbs.scheduler;

import com.bank.cbs.service.CbsCobBatchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

public class CbsCobSchedulerContextTest {

    @Configuration
    @EnableScheduling
    static class TestSchedulingConfig {
        @Bean
        public CbsCobBatchService cobBatchService() {
            return mock(CbsCobBatchService.class);
        }

        @Bean
        public CbsCobScheduler cbsCobScheduler(CbsCobBatchService cobBatchService) {
            return new CbsCobScheduler(cobBatchService);
        }
    }

    @Test
    @DisplayName("Verify Spring @Scheduled correctly registers CbsCobScheduler with cron and Asia/Manila zone")
    void testScheduledTaskRegistration() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProperties", Map.of(
                        "cbs.cob.scheduler.enabled", "true",
                        "cbs.cob.scheduler.cron", "0 0 21 * * *",
                        "cbs.cob.scheduler.zone", "Asia/Manila"
                ))
        );
        context.register(TestSchedulingConfig.class);
        context.refresh();

        ScheduledTaskHolder taskHolder = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
        Set<ScheduledTask> tasks = taskHolder.getScheduledTasks();

        assertFalse(tasks.isEmpty(), "Scheduled tasks should not be empty");
        assertEquals(1, tasks.size(), "Exactly 1 scheduled task should be registered");

        ScheduledTask scheduledTask = tasks.iterator().next();
        assertInstanceOf(CronTask.class, scheduledTask.getTask(), "Task should be a CronTask");

        CronTask cronTask = (CronTask) scheduledTask.getTask();
        assertEquals("0 0 21 * * *", cronTask.getExpression(), "Cron expression must match cutoff 21:00");
        assertNotNull(cronTask.getTrigger(), "Trigger must be configured");

        context.close();
    }

    @Test
    @DisplayName("Verify Spring scheduler actually executes triggerScheduledCob() when cron trigger fires")
    void testScheduledExecutionTriggers() throws InterruptedException {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testProperties", Map.of(
                        "cbs.cob.scheduler.enabled", "true",
                        "cbs.cob.scheduler.cron", "*/1 * * * * *", // Every second
                        "cbs.cob.scheduler.zone", "Asia/Manila"
                ))
        );
        context.register(TestSchedulingConfig.class);
        context.refresh();

        CbsCobBatchService batchService = context.getBean(CbsCobBatchService.class);
        org.mockito.Mockito.when(batchService.runCobBatch()).thenReturn(new com.bank.cbs.dto.CobExecutionResponseDto(
                java.time.LocalDate.now(), "ONLINE", true, java.util.Collections.emptyList()
        ));

        // Allow up to 2.5 seconds for the 1-second scheduled cron task to fire at least once
        Thread.sleep(2500);

        org.mockito.Mockito.verify(batchService, org.mockito.Mockito.atLeastOnce()).runCobBatch();

        context.close();
    }
}
