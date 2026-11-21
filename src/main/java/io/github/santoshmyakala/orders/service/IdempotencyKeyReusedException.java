package io.github.santoshmyakala.orders.service;


public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key %s was already used for a different request".formatted(key));
    }
}
