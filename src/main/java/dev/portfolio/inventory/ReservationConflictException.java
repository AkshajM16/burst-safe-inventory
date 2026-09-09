package dev.portfolio.inventory;

public class ReservationConflictException extends RuntimeException {
    public ReservationConflictException(String message) { super(message); }
}
