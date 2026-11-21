package io.github.santoshmyakala.orders.service;

import java.util.UUID;

public class ConcurrentModificationException extends RuntimeException {

    public ConcurrentModificationException(UUID id) {
        super("Production order %s was changed concurrently; reload and retry".formatted(id));
    }
}
