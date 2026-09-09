package dev.portfolio.inventory;

import java.util.UUID;

public interface ReservationService {
    ReservationOutcome reserve(long itemId, UUID reservationId, int quantity);
}
