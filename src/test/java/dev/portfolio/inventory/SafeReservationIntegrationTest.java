package dev.portfolio.inventory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {"inventory.mode=safe", "spring.datasource.hikari.maximum-pool-size=20"})
class SafeReservationIntegrationTest {
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
    @Autowired JdbcTemplate jdbc;

    @Test
    void simultaneousDemandCannotExceedInventory() throws Exception {
        var item = items.create("safe-burst-" + UUID.randomUUID(), 100);
        List<ReservationOutcome> outcomes = burst(item.id(), 200, i -> UUID.randomUUID());
        assertThat(outcomes).filteredOn(o -> o.status() == ReservationOutcome.Status.SUCCEEDED).hasSize(100);
        assertInvariants(item.id(), 100, 0, 100);
    }

    @Test
    void concurrentDuplicateKeyHasExactlyOneSideEffectAndStableReplay() throws Exception {
        var item = items.create("duplicate-" + UUID.randomUUID(), 10);
        UUID key = UUID.randomUUID();
        List<ReservationOutcome> outcomes = burst(item.id(), 40, i -> key);
        assertThat(outcomes).allMatch(o -> o.status() == ReservationOutcome.Status.SUCCEEDED);
        assertThat(outcomes).filteredOn(ReservationOutcome::replay).hasSize(39);
        assertInvariants(item.id(), 10, 9, 1);

        ReservationOutcome retry = reservations.reserve(item.id(), key, 1);
        assertThat(retry.replay()).isTrue();
        assertThatThrownBy(() -> reservations.reserve(item.id(), key, 2))
            .isInstanceOf(ReservationConflictException.class);
        assertInvariants(item.id(), 10, 9, 1);
    }

    @Test
    void invalidItemDoesNotCreateReservation() {
        UUID key = UUID.randomUUID();
        assertThatThrownBy(() -> reservations.reserve(Long.MAX_VALUE, key, 1))
            .isInstanceOf(ItemNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reservations WHERE reservation_id = ?",
            Integer.class, key)).isZero();
    }

    private List<ReservationOutcome> burst(long itemId, int count,
                                            java.util.function.IntFunction<UUID> keys) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(50)) {
            List<Future<ReservationOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> { gate.await(); return reservations.reserve(itemId, keys.apply(index), 1); }));
            }
            gate.countDown();
            List<ReservationOutcome> outcomes = new ArrayList<>();
            for (Future<ReservationOutcome> future : futures) outcomes.add(future.get());
            return outcomes;
        }
    }

    private void assertInvariants(long itemId, int initial, int expectedStock, int expectedReserved) {
        var item = items.find(itemId).orElseThrow();
        assertThat(item.stock()).isGreaterThanOrEqualTo(0).isEqualTo(expectedStock);
        assertThat(item.reservedQuantity()).isEqualTo(expectedReserved);
        assertThat(item.stock() + item.reservedQuantity()).isEqualTo(initial);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM reservations
             WHERE item_id = ? AND status = 'SUCCEEDED'
            """, Integer.class, itemId)).isEqualTo(expectedReserved);
    }
}
