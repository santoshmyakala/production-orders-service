package io.github.santoshmyakala.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IfMatchTest {

    @Test
    void parsesStrongAndWeakETags() {
        assertThat(OrderController.version("\"3\"")).isEqualTo(3L);
        assertThat(OrderController.version("W/\"7\"")).isEqualTo(7L);
        assertThat(OrderController.version(null)).isNull();
        assertThat(OrderController.version(" ")).isNull();
    }

    @Test
    void rejectsForeignETags() {
        assertThatThrownBy(() -> OrderController.version("\"abc\"")).isInstanceOf(IllegalArgumentException.class);
    }
}
