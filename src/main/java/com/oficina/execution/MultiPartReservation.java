package com.oficina.execution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Configuration
@ConditionalOnProperty(name = "execution.multi-part.enabled", havingValue = "true")
class MultiPartReservationConfiguration {
    @Bean MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }
}

@org.springframework.stereotype.Service
@ConditionalOnProperty(name = "execution.multi-part.enabled", havingValue = "true")
class MultiPartReservationService {
    private final StockService stock;
    private final org.springframework.data.mongodb.core.MongoTemplate mongo;
    MultiPartReservationService(StockService stock, org.springframework.data.mongodb.core.MongoTemplate mongo) { this.stock = stock; this.mongo = mongo; }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void reserve(UUID branch, UUID reservation, List<MultiPartReservationController.Item> items) {
        if (items.stream().map(MultiPartReservationController.Item::partId).distinct().count() != items.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate part ID");
        var ordered = items.stream().sorted(java.util.Comparator.comparing(item -> item.partId().toString())).toList();
        String id = branch + ":" + reservation;
        var manifest = mongo.findById(id, ReservationManifest.class);
        if (manifest != null && !manifest.items().equals(ordered))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation already exists with different items");
        if (manifest != null && manifest.state() != ReservationManifest.State.RESERVED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is already finalized");
        if (manifest == null) mongo.insert(new ReservationManifest(id, ordered));
        // Manifest and reservations commit together; a retry cannot change the set of parts.
        ordered.forEach(item -> stock.reserve(branch, item.partId(), reservation, item.quantity()));
    }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void release(UUID branch, UUID reservation) { finalizeReservation(branch, reservation, ReservationManifest.State.RELEASED); }

    @Transactional(transactionManager = "mongoTransactionManager")
    public void consume(UUID branch, UUID reservation) { finalizeReservation(branch, reservation, ReservationManifest.State.CONSUMED); }

    public void verifyExisting(UUID branch, UUID reservation, List<MultiPartReservationController.Item> items) {
        var manifest = mongo.findById(branch + ":" + reservation, ReservationManifest.class);
        var ordered = items.stream().sorted(java.util.Comparator.comparing(item -> item.partId().toString())).toList();
        if (manifest == null || !manifest.items().equals(ordered) || manifest.state() == ReservationManifest.State.RELEASED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation does not match authorization");
    }

    public void verifyReserved(UUID branch, UUID reservation, List<MultiPartReservationController.Item> items) {
        verifyExisting(branch, reservation, items);
        var manifest = mongo.findById(branch + ":" + reservation, ReservationManifest.class);
        if (manifest.state() != ReservationManifest.State.RESERVED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation is not active");
    }

    private void finalizeReservation(UUID branch, UUID reservation, ReservationManifest.State desired) {
        var manifest = mongo.findById(branch + ":" + reservation, ReservationManifest.class);
        if (manifest == null && desired == ReservationManifest.State.RELEASED) {
            // A cancellation tombstone prevents a delayed reservation from being created later.
            mongo.insert(ReservationManifest.cancelled(branch + ":" + reservation));
            return;
        }
        if (manifest == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found");
        if (manifest.state() == desired) return;
        if (manifest.state() != ReservationManifest.State.RESERVED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reservation already finalized");
        for (var item : manifest.items()) {
            if (desired == ReservationManifest.State.RELEASED) stock.release(branch, item.partId(), reservation);
            else stock.consume(branch, item.partId(), reservation);
        }
        manifest.setState(desired);
        mongo.save(manifest);
    }
}

@org.springframework.data.mongodb.core.mapping.Document("stock_reservation_manifests")
class ReservationManifest {
    enum State { RESERVED, RELEASED, CONSUMED }
    @org.springframework.data.annotation.Id private String id;
    private List<MultiPartReservationController.Item> items;
    private State state;
    private ReservationManifest() { }
    ReservationManifest(String id, List<MultiPartReservationController.Item> items) { this.id = id; this.items = items; this.state = State.RESERVED; }
    static ReservationManifest cancelled(String id) {
        var manifest = new ReservationManifest(id, List.of());
        manifest.state = State.RELEASED;
        return manifest;
    }
    List<MultiPartReservationController.Item> items() { return items; }
    State state() { return state == null ? State.RESERVED : state; }
    void setState(State state) { this.state = state; }
}

@RestController
@RequestMapping("/api/branches/{branch}/stock-reservations")
@ConditionalOnProperty(name = "execution.multi-part.enabled", havingValue = "true")
class MultiPartReservationController {
    record Item(@NotNull UUID partId, @NotNull @Min(1) @Max(1000000) Integer quantity) { }
    record Request(@NotNull @Size(max = 100) List<@NotNull @Valid Item> items) { }
    private final MultiPartReservationService reservations;
    MultiPartReservationController(MultiPartReservationService reservations) { this.reservations = reservations; }
    @PutMapping("/{reservation}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void reserve(@PathVariable UUID branch, @PathVariable UUID reservation, @Valid @RequestBody Request request) {
        reservations.reserve(branch, reservation, request.items());
    }
    @PostMapping("/{reservation}/release") @ResponseStatus(HttpStatus.NO_CONTENT)
    void release(@PathVariable UUID branch, @PathVariable UUID reservation) { reservations.release(branch, reservation); }
    @PostMapping("/{reservation}/consume") @ResponseStatus(HttpStatus.NO_CONTENT)
    void consume(@PathVariable UUID branch, @PathVariable UUID reservation) { reservations.consume(branch, reservation); }
}
