package io.github.santoshmyakala.orders.service;

import io.github.santoshmyakala.orders.domain.IdempotencyKey;
import io.github.santoshmyakala.orders.domain.IdempotencyKeyRepository;
import io.github.santoshmyakala.orders.domain.InvalidTransitionException;
import io.github.santoshmyakala.orders.domain.OrderStatus;
import io.github.santoshmyakala.orders.domain.ProductionOrder;
import io.github.santoshmyakala.orders.domain.ProductionOrderRepository;
import io.github.santoshmyakala.orders.material.InsufficientMaterialException;
import io.github.santoshmyakala.orders.material.MaterialClient;
import io.github.santoshmyakala.orders.material.MaterialServiceUnavailableException;
import io.github.santoshmyakala.orders.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    private final ProductionOrderRepository orders;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final MaterialClient material;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public OrderService(ProductionOrderRepository orders, IdempotencyKeyRepository idempotencyKeys, MaterialClient material,
                        OutboxWriter outbox, TransactionTemplate tx, Clock clock, MeterRegistry meters) {
        this.orders = orders;
        this.idempotencyKeys = idempotencyKeys;
        this.material = material;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    public record NewOrder(String productCode, int quantity, String line, LocalDate dueDate) {}

    /** The order, and whether this call created it (false for an idempotent replay). */
    public record Created(ProductionOrder order, boolean created) {}

    /**
     * Creates an order. With an idempotency key, a retry of the same request returns the original
     * order instead of planning the production twice; the same key with a different request is
     * rejected. Two concurrent requests with one key race on the key's primary key: the loser
     * rolls back and returns the winner's order.
     */
    public Created create(NewOrder request, String idempotencyKey) {
        String hash = fingerprint(request);
        if (idempotencyKey != null) {
            var existing = replay(idempotencyKey, hash);
            if (existing != null) {
                return existing;
            }
        }
        try {
            ProductionOrder order = tx.execute(status -> {
                Instant now = clock.instant();
                String number = "PO-%d-%06d".formatted(Year.now(clock).getValue(), orders.nextOrderNumber());
                ProductionOrder o = orders.save(new ProductionOrder(number, request.productCode(), request.quantity(),
                        request.line(), request.dueDate(), now));
                if (idempotencyKey != null) {
                    idempotencyKeys.saveAndFlush(new IdempotencyKey(idempotencyKey, hash, o.getId(), now));
                }
                outbox.record(o.getId(), "ProductionOrderPlanned", event(o), now);
                return o;
            });
            meters.counter("orders.planned", "line", request.line()).increment();
            return new Created(order, true);
        } catch (DataIntegrityViolationException e) {
            var winner = idempotencyKey == null ? null : replay(idempotencyKey, hash);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
    }

    /** SHA-256 of the request: the same key must come with the same request. */
    private static String fingerprint(NewOrder request) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Created replay(String key, String hash) {
        return idempotencyKeys.findById(key).map(k -> {
            if (!k.getRequestHash().equals(hash)) {
                throw new IdempotencyKeyReusedException(key);
            }
            return new Created(orders.findById(k.getOrderId()).orElseThrow(), false);
        }).orElse(null);
    }

    public ProductionOrder get(UUID id) {
        return orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }

    public Page<ProductionOrder> search(OrderStatus status, String line, Pageable page) {
        return orders.search(status, line, page);
    }

    /**
     * Reserves material, then releases the order. The ERP call happens outside any database
     * transaction, so a slow ERP never holds a connection. The reservation is keyed by order number,
     * so if the release then loses an optimistic-lock race, a retry reuses the same reservation.
     */
    public ProductionOrder release(UUID id, Long expectedVersion) {
        ProductionOrder current = get(id);
        checkVersion(current, expectedVersion);
        if (!current.getStatus().next().contains(OrderStatus.RELEASED)) {
            // Fail before reserving material for an order that cannot be released.
            throw new InvalidTransitionException(current.getOrderNumber(), current.getStatus(), OrderStatus.RELEASED);
        }
        MaterialClient.Reservation reservation;
        try {
            reservation = material.reserve(current.getOrderNumber(), current.getProductCode(), current.getQuantity());
        } catch (InsufficientMaterialException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MaterialServiceUnavailableException(e);
        }
        return transition(id, current.getVersion(), "ProductionOrderReleased",
                o -> o.release(reservation.reservationId(), clock.instant()));
    }

    public ProductionOrder start(UUID id, Long expectedVersion) {
        return transition(id, expectedVersion, "ProductionOrderStarted", o -> o.start(clock.instant()));
    }

    public ProductionOrder complete(UUID id, Long expectedVersion, int good, int scrap) {
        return transition(id, expectedVersion, "ProductionOrderCompleted", o -> o.complete(good, scrap, clock.instant()));
    }

    public ProductionOrder cancel(UUID id, Long expectedVersion) {
        return transition(id, expectedVersion, "ProductionOrderCancelled", o -> o.cancel(clock.instant()));
    }

    private ProductionOrder transition(UUID id, Long expectedVersion, String eventType, Consumer<ProductionOrder> change) {
        try {
            ProductionOrder result = tx.execute(status -> {
                ProductionOrder o = get(id);
                checkVersion(o, expectedVersion);
                change.accept(o);
                ProductionOrder saved = orders.saveAndFlush(o);
                outbox.record(saved.getId(), eventType, event(saved), saved.getUpdatedAt());
                return saved;
            });
            meters.counter("orders.transitions", "to", result.getStatus().name()).increment();
            return result;
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentModificationException(id);
        }
    }

    private static void checkVersion(ProductionOrder o, Long expected) {
        if (expected != null && expected != o.getVersion()) {
            throw new VersionMismatchException(o.getId(), expected, o.getVersion());
        }
    }

    private static Map<String, Object> event(ProductionOrder o) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("orderId", o.getId());
        e.put("orderNumber", o.getOrderNumber());
        e.put("productCode", o.getProductCode());
        e.put("quantity", o.getQuantity());
        e.put("line", o.getLine());
        e.put("status", o.getStatus());
        if (o.getReservationId() != null) {
            e.put("reservationId", o.getReservationId());
        }
        if (o.getGoodQuantity() != null) {
            e.put("goodQuantity", o.getGoodQuantity());
            e.put("scrapQuantity", o.getScrapQuantity());
        }
        return e;
    }
}
