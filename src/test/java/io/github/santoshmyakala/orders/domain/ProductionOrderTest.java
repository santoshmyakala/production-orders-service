package io.github.santoshmyakala.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ProductionOrderTest {

    private static final Instant T0 = Instant.parse("2026-11-20T08:00:00Z");

    private static ProductionOrder planned() {
        return new ProductionOrder("PO-2026-000001", "GEARBOX-12", 40, "LINE-1", LocalDate.parse("2026-12-01"), T0);
    }

    @Test
    void happyPathThroughTheLifecycle() {
        ProductionOrder o = planned();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PLANNED);
        o.release("RES-1", T0.plusSeconds(60));
        o.start(T0.plusSeconds(120));
        o.complete(38, 2, T0.plusSeconds(3600));
        assertThat(o.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(o.getReservationId()).isEqualTo("RES-1");
        assertThat(o.getGoodQuantity()).isEqualTo(38);
        assertThat(o.getScrapQuantity()).isEqualTo(2);
        assertThat(o.getUpdatedAt()).isEqualTo(T0.plusSeconds(3600));
    }

    @Test
    void cannotSkipTheLine() {
        ProductionOrder o = planned();
        assertThatThrownBy(() -> o.start(T0)).isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("PLANNED to IN_PROGRESS");
        assertThatThrownBy(() -> o.complete(1, 0, T0)).isInstanceOf(InvalidTransitionException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.PLANNED);
    }

    @Test
    void anOrderOnTheLineCannotBeCancelled() {
        ProductionOrder o = planned();
        o.release("RES-1", T0);
        o.start(T0);
        assertThatThrownBy(() -> o.cancel(T0)).isInstanceOf(InvalidTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"COMPLETED", "CANCELLED"})
    void terminalStatesAreTerminal(OrderStatus terminal) {
        assertThat(terminal.next()).isEmpty();
    }

    @Test
    void rejectsNonsenseQuantities() {
        assertThatThrownBy(() -> new ProductionOrder("PO-1", "X", 0, "L", LocalDate.now(), T0))
                .isInstanceOf(IllegalArgumentException.class);
        ProductionOrder o = planned();
        o.release("RES-1", T0);
        o.start(T0);
        assertThatThrownBy(() -> o.complete(-1, 0, T0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
    }
}
