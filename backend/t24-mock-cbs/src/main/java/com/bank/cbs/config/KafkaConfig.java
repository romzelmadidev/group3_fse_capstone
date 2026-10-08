package com.bank.cbs.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String TOPIC_TRANSFERS_EVENTS = "banking.transfers.events";
    public static final String TOPIC_BATCH_EVENTS = "banking.batch.events";
    public static final String TOPIC_TRANSFERS_DLQ = "banking.transfers.dlq";

    @Bean
    public NewTopic transfersEventsTopic() {
        return TopicBuilder.name(TOPIC_TRANSFERS_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic batchEventsTopic() {
        return TopicBuilder.name(TOPIC_BATCH_EVENTS)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic transfersDlqTopic() {
        return TopicBuilder.name(TOPIC_TRANSFERS_DLQ)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
