package io.github.santoshmyakala.orders.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(long id, UUID eventId, UUID aggregateId, String eventType, String payload, Instant createdAt) {}
