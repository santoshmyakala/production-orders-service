package io.github.santoshmyakala.orders.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A production order. State changes go through the transition methods, which enforce the lifecycle. */
@Entity
@Table(name = "production_orders")
public class ProductionOrder {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, updatable = false)
    private String orderNumber;

    @Column(nullable = false, updatable = false)
    private String productCode;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(nullable = false)
    private String line;

    @Column(nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    private String reservationId;
    private Integer goodQuantity;
    private Integer scrapQuantity;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    // A wrapper type: null tells Spring Data the entity is new (persist, not merge), since the id is assigned.
    @Version
    private Long version;

    protected ProductionOrder() {
        // JPA
    }

    public ProductionOrder(String orderNumber, String productCode, int quantity, String line, LocalDate dueDate, Instant now) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        this.id = UUID.randomUUID();
        this.orderNumber = Objects.requireNonNull(orderNumber);
        this.productCode = Objects.requireNonNull(productCode);
        this.quantity = quantity;
        this.line = Objects.requireNonNull(line);
        this.dueDate = Objects.requireNonNull(dueDate);
        this.status = OrderStatus.PLANNED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    private void moveTo(OrderStatus target, Instant now) {
        if (!status.next().contains(target)) {
            throw new InvalidTransitionException(orderNumber, status, target);
        }
        status = target;
        updatedAt = now;
    }

    /** Material is reserved; the order can go to the line. */
    public void release(String reservationId, Instant now) {
        moveTo(OrderStatus.RELEASED, now);
        this.reservationId = Objects.requireNonNull(reservationId);
    }

    public void start(Instant now) {
        moveTo(OrderStatus.IN_PROGRESS, now);
    }

    /** Reports the output. Good plus scrap may differ from the planned quantity (over- or under-production). */
    public void complete(int good, int scrap, Instant now) {
        if (good < 0 || scrap < 0) {
            throw new IllegalArgumentException("quantities must not be negative");
        }
        moveTo(OrderStatus.COMPLETED, now);
        this.goodQuantity = good;
        this.scrapQuantity = scrap;
    }

    public void cancel(Instant now) {
        moveTo(OrderStatus.CANCELLED, now);
    }

    public UUID getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public String getProductCode() { return productCode; }
    public int getQuantity() { return quantity; }
    public String getLine() { return line; }
    public LocalDate getDueDate() { return dueDate; }
    public OrderStatus getStatus() { return status; }
    public String getReservationId() { return reservationId; }
    public Integer getGoodQuantity() { return goodQuantity; }
    public Integer getScrapQuantity() { return scrapQuantity; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version == null ? 0 : version; }
}
