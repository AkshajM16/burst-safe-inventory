package dev.portfolio.inventory;

import java.util.UUID;

public record ReservationOutcome(UUID reservationId, long itemId, int quantity,
                                 Status status, boolean replay, String mode) {
    public enum Status { SUCCEEDED, SOLD_OUT }
}
