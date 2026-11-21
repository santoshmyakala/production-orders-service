package io.github.santoshmyakala.orders.domain;

public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(String orderNumber, OrderStatus from, OrderStatus to) {
        super("Order %s cannot go from %s to %s (allowed: %s)".formatted(orderNumber, from, to, from.next()));
    }
}
