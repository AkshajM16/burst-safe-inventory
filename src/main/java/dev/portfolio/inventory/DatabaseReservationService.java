package dev.portfolio.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
public class DatabaseReservationService implements ReservationService {
    private static final Logger log = LoggerFactory.getLogger(DatabaseReservationService.class);
    private final JdbcTemplate jdbc;
    private final InventoryProperties properties;
    private final TransactionTemplate transactions;

    public DatabaseReservationService(JdbcTemplate jdbc, InventoryProperties properties,
                                      PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public ReservationOutcome reserve(long itemId, UUID reservationId, int quantity) {
        return properties.mode().equals("naive")
            ? reserveNaively(itemId, reservationId, quantity)
            : transactions.execute(status -> reserveSafely(itemId, reservationId, quantity));
    }

    private ReservationOutcome reserveNaively(long itemId, UUID reservationId, int quantity) {
        try {
            return transactions.execute(status -> {
                Optional<ReservationOutcome> replay = findReservation(reservationId, "naive");
                if (replay.isPresent()) return validateReplay(replay.get(), itemId, quantity);

                Integer stock = jdbc.query("SELECT stock FROM items WHERE id = ?", rs ->
                    rs.next() ? rs.getInt(1) : null, itemId);
                if (stock == null) throw new ItemNotFoundException(itemId);

                testAidDelay();
                if (stock < quantity) {
                    insertReservation(reservationId, itemId, quantity, 0, "SOLD_OUT");
                    return outcome(reservationId, itemId, quantity, "SOLD_OUT", false, "naive");
                }

                // Deliberately unsafe V1: the checked value is written back without a
                // predicate. Concurrent callers can all observe the same stock and succeed.
                jdbc.update("UPDATE items SET stock = ? WHERE id = ?", stock - quantity, itemId);
                insertReservation(reservationId, itemId, quantity, quantity, "SUCCEEDED");
                return outcome(reservationId, itemId, quantity, "SUCCEEDED", false, "naive");
            });
        } catch (DataIntegrityViolationException duplicate) {
            // A concurrent request may have committed the key while this transaction waited.
            return transactions.execute(status -> findReservation(reservationId, "naive")
                .map(found -> validateReplay(found, itemId, quantity)).orElseThrow(() -> duplicate));
        }
    }

    private ReservationOutcome reserveSafely(long itemId, UUID reservationId, int quantity) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM items WHERE id = ?)",
                Boolean.class, itemId))) throw new ItemNotFoundException(itemId);

        int claimed = jdbc.update("""
            INSERT INTO reservations(reservation_id, item_id, requested_qty, reserved_qty, status)
            VALUES (?, ?, ?, 0, 'SOLD_OUT') ON CONFLICT (reservation_id) DO NOTHING
            """, reservationId, itemId, quantity);

        if (claimed == 0) {
            // PostgreSQL makes ON CONFLICT wait for an uncommitted competing insert.
            // Consequently this read sees the original transaction's final result.
            ReservationOutcome original = findReservation(reservationId, "safe")
                .orElseThrow(() -> new IllegalStateException("idempotency claim disappeared"));
            return validateReplay(original, itemId, quantity);
        }

        int decremented = jdbc.update("""
            UPDATE items SET stock = stock - ?
             WHERE id = ? AND stock >= ?
            """, quantity, itemId, quantity);
        if (decremented == 1) {
            jdbc.update("""
                UPDATE reservations SET status = 'SUCCEEDED', reserved_qty = requested_qty
                 WHERE reservation_id = ?
                """, reservationId);
            return outcome(reservationId, itemId, quantity, "SUCCEEDED", false, "safe");
        }
        return outcome(reservationId, itemId, quantity, "SOLD_OUT", false, "safe");
    }

    private Optional<ReservationOutcome> findReservation(UUID id, String mode) {
        return jdbc.query("""
            SELECT reservation_id, item_id, requested_qty, status::text
              FROM reservations WHERE reservation_id = ?
            """, (rs, row) -> new ReservationOutcome((UUID) rs.getObject(1), rs.getLong(2), rs.getInt(3),
                ReservationOutcome.Status.valueOf(rs.getString(4)), true, mode), id).stream().findFirst();
    }

    private ReservationOutcome validateReplay(ReservationOutcome found, long itemId, int quantity) {
        if (found.itemId() != itemId || found.quantity() != quantity) {
            throw new ReservationConflictException(
                "reservation_id is already bound to item " + found.itemId() + " and quantity " + found.quantity());
        }
        return found;
    }

    private void insertReservation(UUID id, long itemId, int requested, int reserved, String status) {
        jdbc.update("""
            INSERT INTO reservations(reservation_id, item_id, requested_qty, reserved_qty, status)
            VALUES (?, ?, ?, ?, ?::reservation_status)
            """, id, itemId, requested, reserved, status);
    }

    private ReservationOutcome outcome(UUID id, long itemId, int quantity, String status,
                                       boolean replay, String mode) {
        ReservationOutcome result = new ReservationOutcome(id, itemId, quantity,
            ReservationOutcome.Status.valueOf(status), replay, mode);
        log.info("reservation outcome={} item_id={} quantity={} replay={} mode={}",
            status, itemId, quantity, replay, mode);
        return result;
    }

    private void testAidDelay() {
        if (properties.naiveDelayMs() == 0) return;
        try {
            Thread.sleep(properties.naiveDelayMs());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during naive test-aid delay", interrupted);
        }
    }
}
