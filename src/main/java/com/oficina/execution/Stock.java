package com.oficina.execution;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory aggregate; persistence must provide atomic optimistic concurrency. */
public final class Stock {
    private record Reservation(int quantity, boolean released) {}
    private final UUID branchId;
    private final UUID partId;
    private int available;
    private final Map<UUID, Reservation> reservations = new HashMap<>();

    public Stock(UUID branchId, UUID partId, int available) {
        this.branchId = Objects.requireNonNull(branchId);
        this.partId = Objects.requireNonNull(partId);
        if (available < 0) throw new IllegalArgumentException("Negative stock");
        this.available = available;
    }
    public UUID branchId() { return branchId; }
    public UUID partId() { return partId; }
    public int available() { return available; }
    public void reserve(UUID reservationId, int quantity) {
        Objects.requireNonNull(reservationId);
        if (quantity <= 0) throw new IllegalArgumentException("Invalid quantity");
        var old = reservations.get(reservationId);
        if (old != null) {
            if (old.quantity != quantity || old.released) throw new IllegalStateException("Reservation conflict");
            return;
        }
        if (available < quantity) throw new IllegalStateException("Insufficient stock");
        available -= quantity;
        reservations.put(reservationId, new Reservation(quantity, false));
    }
    public void release(UUID reservationId) {
        var old = reservations.get(Objects.requireNonNull(reservationId));
        if (old == null) throw new IllegalStateException("Unknown reservation");
        if (old.released) return;
        available += old.quantity;
        reservations.put(reservationId, new Reservation(old.quantity, true));
    }
}
