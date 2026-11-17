package com.oficina.execution;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/branches/{branch}/parts/{part}/stock")
class StockController {
    record InitialStock(@NotNull @Min(0) Integer available) {}
    record ReservationRequest(@NotNull @Min(1) Integer quantity) {}
    private final StockService service;
    StockController(StockService service) { this.service = service; }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    StockDocument create(@PathVariable UUID branch, @PathVariable UUID part, @Valid @RequestBody InitialStock request) { return service.create(branch, part, request.available()); }
    @GetMapping StockDocument get(@PathVariable UUID branch, @PathVariable UUID part) { return service.get(branch, part); }
    @PutMapping("/reservations/{reservation}") StockDocument reserve(@PathVariable UUID branch, @PathVariable UUID part, @PathVariable UUID reservation, @Valid @RequestBody ReservationRequest request) { return service.reserve(branch, part, reservation, request.quantity()); }
    @PostMapping("/reservations/{reservation}/release") StockDocument release(@PathVariable UUID branch, @PathVariable UUID part, @PathVariable UUID reservation) { return service.release(branch, part, reservation); }
    @PostMapping("/reservations/{reservation}/consume") StockDocument consume(@PathVariable UUID branch, @PathVariable UUID part, @PathVariable UUID reservation) { return service.consume(branch, part, reservation); }
}
