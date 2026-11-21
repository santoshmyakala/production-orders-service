package io.github.santoshmyakala.orders.api;

import io.github.santoshmyakala.orders.domain.OrderStatus;
import io.github.santoshmyakala.orders.domain.ProductionOrder;
import io.github.santoshmyakala.orders.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Production orders. Responses carry an ETag with the order's version; state changes accept
 * {@code If-Match} so a planner and an operator cannot overwrite each other's change unseen.
 */
@RestController
@RequestMapping("/api/orders")
class OrderController { // parameter constraints use Spring's built-in method validation (400, not 500)

    private final OrderService service;

    OrderController(OrderService service) {
        this.service = service;
    }

    record CreateOrderRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z0-9][A-Z0-9-]*", message = "must be upper-case letters, digits and dashes") String productCode,
            @Min(1) @Max(1_000_000) int quantity,
            @NotBlank @Size(max = 32) String line,
            @NotNull @Future LocalDate dueDate) {}

    record CompleteOrderRequest(@PositiveOrZero int goodQuantity, @PositiveOrZero int scrapQuantity) {}

    record OrderResponse(UUID id, String orderNumber, String productCode, int quantity, String line, LocalDate dueDate,
                         OrderStatus status, String reservationId, Integer goodQuantity, Integer scrapQuantity,
                         Instant createdAt, Instant updatedAt, long version) {
        static OrderResponse of(ProductionOrder o) {
            return new OrderResponse(o.getId(), o.getOrderNumber(), o.getProductCode(), o.getQuantity(), o.getLine(),
                    o.getDueDate(), o.getStatus(), o.getReservationId(), o.getGoodQuantity(), o.getScrapQuantity(),
                    o.getCreatedAt(), o.getUpdatedAt(), o.getVersion());
        }
    }

    record PageResponse(List<OrderResponse> items, int page, int size, long totalItems) {
        static PageResponse of(Page<ProductionOrder> p) {
            return new PageResponse(p.map(OrderResponse::of).getContent(), p.getNumber(), p.getSize(), p.getTotalElements());
        }
    }

    @PostMapping
    ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest body,
                                         @RequestHeader(name = "Idempotency-Key", required = false)
                                         @Size(min = 8, max = 128) String idempotencyKey) {
        var result = service.create(new OrderService.NewOrder(body.productCode(), body.quantity(), body.line(), body.dueDate()),
                idempotencyKey);
        var order = result.order();
        var response = result.created()
                ? ResponseEntity.created(URI.create("/api/orders/" + order.getId()))
                : ResponseEntity.ok().header("Idempotent-Replayed", "true");
        return response.eTag(etag(order)).body(OrderResponse.of(order));
    }

    @GetMapping("/{id}")
    ResponseEntity<OrderResponse> get(@PathVariable UUID id) {
        return ok(service.get(id));
    }

    @GetMapping
    PageResponse search(@RequestParam(required = false) OrderStatus status,
                        @RequestParam(required = false) String line,
                        @RequestParam(defaultValue = "0") @Min(0) int page,
                        @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return PageResponse.of(service.search(status, line, PageRequest.of(page, size)));
    }

    @PostMapping("/{id}/release")
    ResponseEntity<OrderResponse> release(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ok(service.release(id, version(ifMatch)));
    }

    @PostMapping("/{id}/start")
    ResponseEntity<OrderResponse> start(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ok(service.start(id, version(ifMatch)));
    }

    @PostMapping("/{id}/complete")
    ResponseEntity<OrderResponse> complete(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                           @Valid @RequestBody CompleteOrderRequest body) {
        return ok(service.complete(id, version(ifMatch), body.goodQuantity(), body.scrapQuantity()));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<OrderResponse> cancel(@PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ok(service.cancel(id, version(ifMatch)));
    }

    private static ResponseEntity<OrderResponse> ok(ProductionOrder o) {
        return ResponseEntity.ok().eTag(etag(o)).body(OrderResponse.of(o));
    }

    private static String etag(ProductionOrder o) {
        return "\"" + o.getVersion() + "\"";
    }

    /** Parses {@code If-Match: "3"} (or W/"3"); null when absent. */
    static Long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        String v = ifMatch.trim().replaceFirst("^W/", "").replace("\"", "");
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be an ETag returned by this API, e.g. \"3\"");
        }
    }
}
