package dev.portfolio.inventory;

import dev.portfolio.inventory.ApiModels.CreateItemRequest;
import dev.portfolio.inventory.ApiModels.ItemView;
import dev.portfolio.inventory.ApiModels.ReserveRequest;
import dev.portfolio.inventory.ApiModels.ReserveResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/items")
public class InventoryController {
    private final InventoryRepository items;
    private final ReservationService reservations;

    public InventoryController(InventoryRepository items, ReservationService reservations) {
        this.items = items;
        this.reservations = reservations;
    }

    @PostMapping
    public ResponseEntity<ItemView> create(@RequestBody CreateItemRequest request) {
        if (request.sku() == null || request.sku().isBlank() || request.sku().length() > 120) {
            throw new InvalidRequestException("sku must contain 1 to 120 characters");
        }
        if (request.initialStock() == null || request.initialStock() <= 0) {
            throw new InvalidRequestException("initialStock must be positive");
        }
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(items.create(request.sku().trim(), request.initialStock()));
    }

    @GetMapping("/{id}")
    public ItemView get(@PathVariable long id) {
        if (id <= 0) throw new InvalidRequestException("item id must be positive");
        return items.find(id).orElseThrow(() -> new ItemNotFoundException(id));
    }

    @PostMapping("/{id}/reserve")
    public ResponseEntity<ReserveResponse> reserve(@PathVariable long id, @RequestBody ReserveRequest request) {
        if (id <= 0) throw new InvalidRequestException("item id must be positive");
        if (request.reservationId() == null) throw new InvalidRequestException("reservationId is required");
        if (request.quantity() == null || request.quantity() <= 0) {
            throw new InvalidRequestException("quantity must be positive");
        }
        MDC.put("reservationId", request.reservationId().toString());
        ReservationOutcome result = reservations.reserve(id, request.reservationId(), request.quantity());
        ReserveResponse body = new ReserveResponse(result.reservationId(), result.itemId(), result.quantity(),
            result.status().name(), result.replay(), result.mode(),
            result.status() == ReservationOutcome.Status.SOLD_OUT ? "insufficient stock" : null);
        HttpStatus status = result.replay() ? HttpStatus.OK
            : result.status() == ReservationOutcome.Status.SUCCEEDED ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(body);
    }
}
