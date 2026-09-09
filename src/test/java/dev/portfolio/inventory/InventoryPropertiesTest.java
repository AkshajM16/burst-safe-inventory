package dev.portfolio.inventory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryPropertiesTest {
    @Test
    void refusesNaiveModeWithoutExplicitUnsafeFlag() {
        assertThatThrownBy(() -> new InventoryProperties("naive", false, 0))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ALLOW_UNSAFE_MODE");
    }
}
