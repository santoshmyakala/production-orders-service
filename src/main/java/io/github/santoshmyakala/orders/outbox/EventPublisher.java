package io.github.santoshmyakala.orders.outbox;

/**
 * Sends an event to the message broker. Implementations must be idempotent per {@code eventId}
 * or tolerate duplicates: the relay delivers at least once.
 */
public interface EventPublisher {

    void publish(OutboxEvent event) throws Exception;
}
