package io.github.santoshmyakala.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Always inserted, never merged: with an assigned primary key, Spring Data's {@code save} would
 * otherwise treat a second request's key as existing and silently overwrite the first one
 * (caught by the concurrency test). As a new entity, the duplicate fails on the primary key.
 */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key")
    private String key;

    @Column(nullable = false)
    private String requestHash;

    @Column(nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private Instant createdAt;

    protected IdempotencyKey() {
        // JPA
    }

    public IdempotencyKey(String key, String requestHash, UUID orderId, Instant createdAt) {
        this.key = key;
        this.requestHash = requestHash;
        this.orderId = orderId;
        this.createdAt = createdAt;
    }

    @Override
    public String getId() { return key; }

    @Override
    public boolean isNew() { return true; } // keys are immutable once written

    public String getRequestHash() { return requestHash; }
    public UUID getOrderId() { return orderId; }
}
