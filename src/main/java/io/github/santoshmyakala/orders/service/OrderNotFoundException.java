package io.github.santoshmyakala.orders.service;

import java.util.UUID;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID id) {
        super("Production order %s not found".formatted(id));
    }
}
