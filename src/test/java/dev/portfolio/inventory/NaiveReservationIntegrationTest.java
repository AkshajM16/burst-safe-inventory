package dev.portfolio.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "inventory.mode=naive", "inventory.allow-unsafe=true", "inventory.naive-delay-ms=100",
    "spring.datasource.hikari.maximum-pool-size=20"
})
class NaiveReservationIntegrationTest {
    static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String external = System.getenv("TEST_DB_URL");
        if (external != null) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> "inventory");
            registry.add("spring.datasource.password", () -> "inventory");
        } else {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
    }

    @Autowired InventoryRepository items;
    @Autowired ReservationService reservations;

    @Test
    void controlledDelayReproducesLostUpdateOversell() throws Exception {
        var item = items.create("naive-race-" + UUID.randomUUID(), 1);
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(10)) {
            var futures = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> executor.submit(() -> {
                    gate.await(); return reservations.reserve(item.id(), UUID.randomUUID(), 1);
                })).toList();
            gate.countDown();
            long successes = 0;
            for (var future : futures) {
                if (future.get().status() == ReservationOutcome.Status.SUCCEEDED) successes++;
            }
            assertThat(successes).as("more than one caller reserved the single unit").isGreaterThan(1);
        }
        var finalItem = items.find(item.id()).orElseThrow();
        assertThat(finalItem.stock()).isZero();
        assertThat(finalItem.reservedQuantity()).isGreaterThan(1);
        assertThat(finalItem.stock() + finalItem.reservedQuantity()).isNotEqualTo(finalItem.initialStock());
    }
}
