package io.github.santoshmyakala.orders.domain;

import java.util.Set;

/** Lifecycle of a production order. */
public enum OrderStatus {
    PLANNED, RELEASED, IN_PROGRESS, COMPLETED, CANCELLED;

    /** States this one may move to. */
    public Set<OrderStatus> next() {
        return switch (this) {
            case PLANNED -> Set.of(RELEASED, CANCELLED);
            case RELEASED -> Set.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> Set.of(COMPLETED);
            case COMPLETED, CANCELLED -> Set.of();
        };
    }
}
