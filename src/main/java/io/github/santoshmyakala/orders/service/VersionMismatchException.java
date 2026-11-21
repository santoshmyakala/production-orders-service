package io.github.santoshmyakala.orders.service;

import java.util.UUID;

/** The client's If-Match version is stale. */
public class VersionMismatchException extends RuntimeException {

    public VersionMismatchException(UUID id, long expected, long actual) {
        super("Production order %s is at version %d, not %d; reload it".formatted(id, actual, expected));
    }
}
