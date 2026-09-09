package dev.portfolio.inventory;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(String mode, boolean allowUnsafe, long naiveDelayMs) {
    public InventoryProperties {
        mode = mode == null ? "safe" : mode.toLowerCase();
        if (!mode.equals("safe") && !mode.equals("naive")) {
            throw new IllegalArgumentException("inventory.mode must be safe or naive");
        }
        if (mode.equals("naive") && !allowUnsafe) {
            throw new IllegalStateException("Refusing to start naive mode unless ALLOW_UNSAFE_MODE=true");
        }
        if (naiveDelayMs < 0 || naiveDelayMs > 10_000) {
            throw new IllegalArgumentException("naive-delay-ms must be between 0 and 10000");
        }
    }
}
