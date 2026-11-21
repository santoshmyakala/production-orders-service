package io.github.santoshmyakala.orders.material;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Reserves material in the ERP / warehouse system before an order is released to the line.
 *
 * <p>Reservations are keyed by order number, so the ERP treats a retried call as the same
 * reservation: retrying is safe. Timeouts are set on the RestClient; transient failures are
 * retried with backoff, and a circuit breaker stops calling an ERP that keeps failing, so a slow
 * ERP cannot exhaust this service's threads.
 */
@Component
public class MaterialClient {

    static final String ERP = "erp";

    private final RestClient erp;

    public MaterialClient(RestClient erpRestClient) {
        this.erp = erpRestClient;
    }

    public record ReservationRequest(String orderNumber, String productCode, int quantity) {}

    public record Reservation(String reservationId) {}

    /**
     * <p>Retry wraps the circuit breaker (resilience4j's default aspect order), so each attempt is
     * recorded by the breaker and an open circuit ({@code CallNotPermittedException}) is not retried.
     * There is deliberately no fallback here: a fallback would convert the exception before Retry
     * sees it and silently disable retries. Callers map failures to
     * {@link MaterialServiceUnavailableException}.
     *
     * @throws InsufficientMaterialException if the ERP has too little stock (a business answer:
     *     not retried, not counted as a failure by the circuit breaker)
     */
    @Retry(name = ERP)
    @CircuitBreaker(name = ERP)
    public Reservation reserve(String orderNumber, String productCode, int quantity) {
        try {
            Reservation r = erp.post()
                    .uri("/reservations")
                    .header("Idempotency-Key", "reserve-" + orderNumber)
                    .body(new ReservationRequest(orderNumber, productCode, quantity))
                    .retrieve()
                    .body(Reservation.class);
            if (r == null || r.reservationId() == null || r.reservationId().isBlank()) {
                throw new IllegalStateException("ERP returned no reservation id");
            }
            return r;
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                throw new InsufficientMaterialException(productCode, quantity);
            }
            throw e;
        }
    }
}
