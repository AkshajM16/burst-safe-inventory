package dev.portfolio.inventory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonAlias;

import java.time.Instant;
import java.util.UUID;

public final class ApiModels {
    private ApiModels() {}

    public record CreateItemRequest(String sku, @JsonAlias("initial_stock") Integer initialStock) {}
    public record ItemView(long id, String sku, int initialStock, int stock,
                           long successfulReservations, long reservedQuantity, Instant createdAt) {}
    public record ReserveRequest(@JsonAlias({"reservation_id", "idempotency_key"}) UUID reservationId,
                                 Integer quantity) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReserveResponse(UUID reservationId, long itemId, int quantity, String status,
                                  boolean replay, String mode, String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorResponse(String code, String message, String requestId) {}
}
