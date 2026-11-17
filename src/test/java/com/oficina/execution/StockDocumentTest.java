package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockDocumentTest {
    @Test void reservationAndReleaseCanBeRepeatedWithoutChangingStockAgain() {
        var branch = UUID.randomUUID(); var part = UUID.randomUUID(); var reservation = UUID.randomUUID();
        var stock = new StockDocument(branch, part, 5);
        stock.reserve(reservation, 2); stock.reserve(reservation, 2);
        assertEquals(3, stock.getAvailable());
        assertThrows(IllegalStateException.class, () -> stock.reserve(reservation, 1));
        stock.release(reservation); stock.release(reservation);
        assertEquals(5, stock.getAvailable());
        assertThrows(IllegalStateException.class, () -> stock.reserve(reservation, 2));
        assertEquals(StockDocument.key(branch, part), StockDocument.key(branch, part));
        assertNotEquals(StockDocument.key(branch, part), StockDocument.key(UUID.randomUUID(), part));
    }
    @Test void insufficientStockDoesNotCreateReservation() {
        var stock = new StockDocument(UUID.randomUUID(), UUID.randomUUID(), 1);
        var reservation = UUID.randomUUID();
        assertThrows(IllegalStateException.class, () -> stock.reserve(reservation, 2));
        assertThrows(IllegalStateException.class, () -> stock.release(reservation));
        assertEquals(1, stock.getAvailable());
    }
    @Test void consumptionIsIdempotentAndCannotBeReleased() {
        var stock = new StockDocument(UUID.randomUUID(), UUID.randomUUID(), 5);
        var reservation = UUID.randomUUID();
        stock.reserve(reservation, 2);
        stock.consume(reservation); stock.consume(reservation);
        stock.reserve(reservation, 2);
        assertEquals(3, stock.getAvailable());
        assertThrows(IllegalStateException.class, () -> stock.release(reservation));
        assertThrows(IllegalStateException.class, () -> stock.consume(UUID.randomUUID()));
    }
    @Test void rejectsInvalidQuantitiesAndConsumptionAfterRelease() {
        assertThrows(IllegalArgumentException.class, () -> new StockDocument(UUID.randomUUID(), UUID.randomUUID(), -1));
        var stock = new StockDocument(UUID.randomUUID(), UUID.randomUUID(), 5);
        var reservation = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> stock.reserve(reservation, -1));
        stock.reserve(reservation, 2); stock.release(reservation);
        assertThrows(IllegalStateException.class, () -> stock.consume(reservation));
        assertEquals(5, stock.getAvailable());
    }
}
