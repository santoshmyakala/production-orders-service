package io.github.santoshmyakala.orders.api;

import io.github.santoshmyakala.orders.domain.InvalidTransitionException;
import io.github.santoshmyakala.orders.material.InsufficientMaterialException;
import io.github.santoshmyakala.orders.material.MaterialServiceUnavailableException;
import io.github.santoshmyakala.orders.service.ConcurrentModificationException;
import io.github.santoshmyakala.orders.service.IdempotencyKeyReusedException;
import io.github.santoshmyakala.orders.service.OrderNotFoundException;
import io.github.santoshmyakala.orders.service.VersionMismatchException;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Maps domain failures to RFC 9457 problem details with a stable {@code type} per failure. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("https://errors.orders.example/" + type));
        p.setTitle(type.replace('-', ' '));
        return p;
    }

    /** Lists every invalid field, so a client can fix the request in one go. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return validationFailed(errors);
    }

    /** Same format when the method also validates headers or query parameters (Spring's method validation). */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new TreeMap<>();
        e.getParameterValidationResults().forEach(result -> {
            if (result instanceof ParameterErrors body) {
                body.getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
            } else {
                String name = result.getMethodParameter().getParameterName();
                result.getResolvableErrors().forEach(err -> errors.putIfAbsent(name, err.getDefaultMessage()));
            }
        });
        return validationFailed(errors);
    }

    private static ResponseEntity<Object> validationFailed(Map<String, String> errors) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation-failed", "The request has invalid fields.");
        p.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(p);
    }

    @ExceptionHandler
    ProblemDetail notFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail invalidTransition(InvalidTransitionException e) {
        return problem(HttpStatus.CONFLICT, "invalid-transition", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail stale(VersionMismatchException e) {
        return problem(HttpStatus.PRECONDITION_FAILED, "stale-version", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail concurrent(ConcurrentModificationException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-modification", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail insufficientMaterial(InsufficientMaterialException e) {
        return problem(HttpStatus.CONFLICT, "insufficient-material", e.getMessage());
    }

    /** The ERP is down or the circuit is open: the client should retry later, not now. */
    @ExceptionHandler
    ResponseEntity<ProblemDetail> erpUnavailable(MaterialServiceUnavailableException e) {
        log.warn("material reservation failed: {}", e.getCause() == null ? e.getMessage() : e.getCause().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "material-service-unavailable",
                        "Material could not be reserved because the ERP is unavailable. The order is unchanged; retry later."));
    }

    @ExceptionHandler
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", e.getMessage());
    }
}
