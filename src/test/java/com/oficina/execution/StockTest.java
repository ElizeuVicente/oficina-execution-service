package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockTest {
    @Test void reserveAndReleaseAreIdempotent() {
        var stock = new Stock(UUID.randomUUID(), UUID.randomUUID(), 5);
        var id = UUID.randomUUID();
        stock.reserve(id, 2); stock.reserve(id, 2);
        assertEquals(3, stock.available());
        stock.release(id); stock.release(id);
        assertEquals(5, stock.available());
        assertThrows(IllegalStateException.class, () -> stock.reserve(id, 2));
    }
    @Test void rejectsInvalidOrConflictingReservations() {
        var stock = new Stock(UUID.randomUUID(), UUID.randomUUID(), 3);
        var id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(id, 0));
        assertThrows(IllegalStateException.class, () -> stock.reserve(id, 4));
        stock.reserve(id, 2);
        assertThrows(IllegalStateException.class, () -> stock.reserve(id, 1));
        assertThrows(IllegalStateException.class, () -> stock.release(UUID.randomUUID()));
        assertEquals(1, stock.available());
    }
}
