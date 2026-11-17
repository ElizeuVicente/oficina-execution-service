package com.oficina.execution;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("stock")
public class StockDocument {
    // Legacy documents have no consumed field; null means the reservation is not consumed.
    record Reservation(int quantity, boolean released, Boolean consumed) {}
    @Id private String id;
    private String branchId;
    private String partId;
    private int available;
    private Map<String, Reservation> reservations = new HashMap<>();
    @Version private Long version;
    protected StockDocument() {}
    StockDocument(UUID branchId, UUID partId, int available) {
        if (available < 0) throw new IllegalArgumentException("Stock cannot be negative");
        this.id = key(branchId, partId); this.branchId = branchId.toString();
        this.partId = partId.toString(); this.available = available;
    }
    static String key(UUID branchId, UUID partId) { return branchId + ":" + partId; }
    void reserve(UUID reservationId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be positive");
        String key = reservationId.toString();
        Reservation old = reservations.get(key);
        if (old != null) {
            if (old.quantity() != quantity || old.released()) throw new IllegalStateException("Reservation conflict");
            return;
        }
        if (available < quantity) throw new InsufficientStockException();
        available -= quantity;
        reservations.put(key, new Reservation(quantity, false, false));
    }
    void release(UUID reservationId) {
        String key = reservationId.toString();
        Reservation old = reservations.get(key);
        if (old == null) throw new IllegalStateException("Unknown reservation");
        if (old.released()) return;
        if (Boolean.TRUE.equals(old.consumed())) throw new IllegalStateException("Consumed stock cannot be released");
        available += old.quantity();
        reservations.put(key, new Reservation(old.quantity(), true, false));
    }
    void consume(UUID reservationId) {
        String key = reservationId.toString();
        Reservation old = reservations.get(key);
        if (old == null) throw new IllegalStateException("Unknown reservation");
        if (old.released()) throw new IllegalStateException("Released reservation cannot be consumed");
        if (Boolean.TRUE.equals(old.consumed())) return;
        reservations.put(key, new Reservation(old.quantity(), false, true));
    }
    public String getBranchId() { return branchId; }
    public String getPartId() { return partId; }
    public int getAvailable() { return available; }
    public Long getVersion() { return version; }
}

class InsufficientStockException extends IllegalStateException {
    InsufficientStockException() { super("Insufficient stock"); }
}
