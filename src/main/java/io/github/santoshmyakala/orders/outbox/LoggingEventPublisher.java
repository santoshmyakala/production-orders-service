package io.github.santoshmyakala.orders.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default publisher: writes events to the log. A broker adapter (Kafka, Azure Event Hubs, SQS)
 * replaces it by declaring its own {@link EventPublisher} bean.
 */
@Configuration
class LoggingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Bean
    @ConditionalOnMissingBean
    EventPublisher eventPublisher() {
        return event -> log.info("event {} {} for order {}: {}", event.eventType(), event.eventId(), event.aggregateId(), event.payload());
    }
}
