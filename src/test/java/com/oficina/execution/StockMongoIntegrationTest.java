package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {"execution.service-token=local-test-token-01234567890123456789", "execution.multi-part.enabled=true",
        "execution.allowed-branches=11111111-1111-4111-8111-111111111111"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StockMongoIntegrationTest {
    @Container static final MongoDBContainer mongo = new MongoDBContainer("mongo:7");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongo::getReplicaSetUrl);
    }
    @Autowired MongoTemplate mongoTemplate;
    @Autowired StockService stock;
    @Autowired StockRepository repository;
    @Autowired MultiPartReservationService multiPart;
    @Autowired WorkOrderAuthorization authorization;
    @Autowired WorkOrderService workOrders;
    @Autowired PartCatalogController catalog;
    @Autowired ServiceCatalogController serviceCatalog;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;

    @Test void exposesOpenApiForTheService() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    @Test void apiEnforcesBranchAllowlistEvenWithValidServiceToken() throws Exception {
        String token = "local-test-token-01234567890123456789";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/branches/{branch}/parts/{part}/stock",
                "11111111-1111-4111-8111-111111111111", UUID.randomUUID()).header("X-Service-Token", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/branches/{branch}/parts/{part}/stock",
                UUID.randomUUID(), UUID.randomUUID()).header("X-Service-Token", token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void catalogImportsAreIdempotentAndNeverOverwriteConflicts() {
        UUID branch = UUID.randomUUID(), part = UUID.randomUUID(), service = UUID.randomUUID();
        var partRequest = new PartCatalogController.Request("Filtro", new java.math.BigDecimal("15.50"));
        catalog.importPart(branch, part, partRequest); catalog.importPart(branch, part, partRequest);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> catalog.importPart(branch, part,
                new PartCatalogController.Request("Filtro", new java.math.BigDecimal("20.00"))));
        assertEquals(0, new java.math.BigDecimal("15.50").compareTo(catalog.get(branch, part).unitPrice()));
        var serviceRequest = new ServiceCatalogController.Request("Reparo", new java.math.BigDecimal("50.00"));
        serviceCatalog.importService(branch, service, serviceRequest); serviceCatalog.importService(branch, service, serviceRequest);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> serviceCatalog.importService(branch, service,
                new ServiceCatalogController.Request("Outro reparo", new java.math.BigDecimal("50.00"))));
        assertEquals("Reparo", serviceCatalog.get(branch, service).description());
    }

    @Test void serviceCatalogPersistsBranchSpecificPrices() {
        UUID branch = UUID.randomUUID(), other = UUID.randomUUID(), service = UUID.randomUUID();
        serviceCatalog.put(branch, service, new ServiceCatalogController.Request("Reparo", new java.math.BigDecimal("100.00")));
        serviceCatalog.put(other, service, new ServiceCatalogController.Request("Reparo", new java.math.BigDecimal("125.00")));
        assertEquals(0, new java.math.BigDecimal("100.00").compareTo(serviceCatalog.get(branch, service).unitPrice()));
        assertEquals(0, new java.math.BigDecimal("125.00").compareTo(serviceCatalog.get(other, service).unitPrice()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> serviceCatalog.get(UUID.randomUUID(), service));
    }

    @Test void catalogPersistsPricesSeparatelyForEachBranchWithoutChangingStock() {
        UUID branch = UUID.randomUUID(), other = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5);
        catalog.put(branch, part, new PartCatalogController.Request("Filtro", new java.math.BigDecimal("15.50")));
        catalog.put(other, part, new PartCatalogController.Request("Filtro", new java.math.BigDecimal("20.00")));
        assertEquals(0, new java.math.BigDecimal("15.50").compareTo(catalog.get(branch, part).unitPrice()));
        assertEquals(0, new java.math.BigDecimal("20.00").compareTo(catalog.get(other, part).unitPrice()));
        catalog.put(branch, part, new PartCatalogController.Request("Filtro novo", new java.math.BigDecimal("17.00")));
        assertEquals(0, new java.math.BigDecimal("17.00").compareTo(catalog.get(branch, part).unitPrice()));
        assertEquals(5, stock.get(branch, part).getAvailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> catalog.get(UUID.randomUUID(), part));
    }

    @Test void sagaAuthorizationRequiresTheExistingReservationAndConsumesIt() {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID(), saga = UUID.randomUUID(), attempt = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5); workOrders.create(branch, order);
        workOrders.update(branch, order, WorkOrder::startDiagnosis);
        workOrders.update(branch, order, work -> work.completeDiagnosis("Diagnosis"));
        var items = java.util.List.of(new MultiPartReservationController.Item(part, 2));
        assertThrows(RuntimeException.class, () -> authorization.authorize(branch, order, attempt, saga, items, true));
        assertEquals(WorkOrder.Status.AWAITING_PAYMENT, workOrders.get(branch, order).getStatus());
        multiPart.reserve(branch, saga, items);
        authorization.authorize(branch, order, attempt, saga, items, true);
        authorization.authorize(branch, order, attempt, saga, items, true);
        authorization.start(branch, order); authorization.start(branch, order);
        assertEquals(ReservationManifest.State.CONSUMED, mongoTemplate.findById(branch + ":" + saga, ReservationManifest.class).state());
        assertEquals(3, stock.get(branch, part).getAvailable());
        assertThrows(RuntimeException.class, () -> authorization.cancel(branch, order, saga));
    }

    @Test void reversalBeforeAuthorizationBlocksLateAuthorization() {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID(), saga = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5); workOrders.create(branch, order);
        workOrders.update(branch, order, WorkOrder::startDiagnosis);
        workOrders.update(branch, order, work -> work.completeDiagnosis("Diagnosis"));
        var items = java.util.List.of(new MultiPartReservationController.Item(part, 2));
        multiPart.reserve(branch, saga, items);
        authorization.cancel(branch, order, saga);
        assertThrows(RuntimeException.class, () -> authorization.authorize(branch, order, UUID.randomUUID(), saga, items, true));
        assertEquals(WorkOrder.Status.CANCELLED, workOrders.get(branch, order).getStatus());
        assertEquals(5, stock.get(branch, part).getAvailable());
    }

    @Test void releaseBeforeReservationPreventsDelayedReservation() {
        UUID branch = UUID.randomUUID(), reservation = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5);
        multiPart.release(branch, reservation); multiPart.release(branch, reservation);
        assertThrows(RuntimeException.class, () -> multiPart.reserve(branch, reservation,
                java.util.List.of(new MultiPartReservationController.Item(part, 2))));
        assertEquals(5, stock.get(branch, part).getAvailable());
        assertEquals(ReservationManifest.State.RELEASED, mongoTemplate.findById(branch + ":" + reservation, ReservationManifest.class).state());
    }

    @Test void serviceOnlyOrderHasAnIdempotentEmptyReservation() {
        UUID branch = UUID.randomUUID(), reservation = UUID.randomUUID();
        multiPart.reserve(branch, reservation, java.util.List.of());
        multiPart.reserve(branch, reservation, java.util.List.of());
        multiPart.release(branch, reservation); multiPart.release(branch, reservation);
        assertEquals(ReservationManifest.State.RELEASED, mongoTemplate.findById(branch + ":" + reservation, ReservationManifest.class).state());
    }

    @Test void concurrentReleaseAndReservationCannotLeaveStockReservedAfterReleaseCompletes() throws Exception {
        UUID branch = UUID.randomUUID(), reservation = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5);
        var items = java.util.List.of(new MultiPartReservationController.Item(part, 2));
        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var reserve = executor.submit(() -> { start.await(); try { multiPart.reserve(branch, reservation, items); } catch (RuntimeException ignored) { } return null; });
            var release = executor.submit(() -> { start.await(); try { multiPart.release(branch, reservation); } catch (RuntimeException ignored) { } return null; });
            start.countDown(); reserve.get(30, java.util.concurrent.TimeUnit.SECONDS); release.get(30, java.util.concurrent.TimeUnit.SECONDS);
            // The caller retries if the competing MongoDB transaction aborted.
            multiPart.release(branch, reservation);
            assertEquals(5, stock.get(branch, part).getAvailable());
            assertEquals(ReservationManifest.State.RELEASED, mongoTemplate.findById(branch + ":" + reservation, ReservationManifest.class).state());
            assertThrows(RuntimeException.class, () -> multiPart.reserve(branch, reservation, items));
        } finally { executor.shutdownNow(); }
    }

    @Test void authorizationReservesAtomicallyAndStartConsumesExactlyOnce() {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID(), attempt = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5);
        workOrders.create(branch, order);
        workOrders.update(branch, order, WorkOrder::startDiagnosis);
        workOrders.update(branch, order, work -> work.completeDiagnosis("Diagnosis"));
        var tooMany = java.util.List.of(new MultiPartReservationController.Item(part, 6));
        assertThrows(RuntimeException.class, () -> authorization.authorize(branch, order, attempt, tooMany));
        assertEquals(WorkOrder.Status.AWAITING_PAYMENT, workOrders.get(branch, order).getStatus());
        assertEquals(5, stock.get(branch, part).getAvailable());
        var items = java.util.List.of(new MultiPartReservationController.Item(part, 2));
        authorization.authorize(branch, order, attempt, items);
        authorization.authorize(branch, order, attempt, items);
        assertEquals(3, stock.get(branch, part).getAvailable());
        assertThrows(IllegalStateException.class, () -> authorization.authorize(branch, order, UUID.randomUUID(), items));
        authorization.start(branch, order);
        authorization.start(branch, order);
        authorization.authorize(branch, order, attempt, items);
        assertEquals(WorkOrder.Status.IN_PROGRESS, workOrders.get(branch, order).getStatus());
        assertEquals(ReservationManifest.State.CONSUMED, mongoTemplate.findById(branch + ":" + order, ReservationManifest.class).state());
        assertThrows(RuntimeException.class, () -> authorization.cancel(branch, order));
    }

    @Test void cancellationReleasesReservedStockAndCannotRestart() {
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(branch, part, 5); workOrders.create(branch, order);
        workOrders.update(branch, order, WorkOrder::startDiagnosis);
        workOrders.update(branch, order, work -> work.completeDiagnosis("Diagnosis"));
        authorization.authorize(branch, order, UUID.randomUUID(), java.util.List.of(new MultiPartReservationController.Item(part, 2)));
        authorization.cancel(branch, order); authorization.cancel(branch, order);
        assertEquals(5, stock.get(branch, part).getAvailable());
        assertEquals(WorkOrder.Status.CANCELLED, workOrders.get(branch, order).getStatus());
        assertThrows(RuntimeException.class, () -> authorization.start(branch, order));
    }

    @Test void multiPartReservationRollsBackOnInsufficientStockAndIsIdempotent() {
        UUID branch = UUID.randomUUID(), operation = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
        stock.create(branch, first, 5); stock.create(branch, second, 1);
        var request = java.util.List.of(new MultiPartReservationController.Item(first, 2), new MultiPartReservationController.Item(second, 2));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.reserve(branch, operation, request));
        assertEquals(5, stock.get(branch, first).getAvailable());
        assertNull(mongoTemplate.findById(branch + ":" + operation, ReservationManifest.class));
        var available = java.util.List.of(new MultiPartReservationController.Item(first, 2), new MultiPartReservationController.Item(second, 1));
        multiPart.reserve(branch, operation, available);
        multiPart.reserve(branch, operation, available);
        assertEquals(3, stock.get(branch, first).getAvailable());
        assertEquals(0, stock.get(branch, second).getAvailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.reserve(branch, operation, request));
    }

    @Test void multiPartReleaseAndConsumptionAreIdempotentAndMutuallyExclusive() {
        UUID branch = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        stock.create(branch, first, 10); stock.create(branch, second, 10);
        var items = java.util.List.of(new MultiPartReservationController.Item(first, 2), new MultiPartReservationController.Item(second, 3));
        UUID released = UUID.randomUUID();
        multiPart.reserve(branch, released, items); multiPart.release(branch, released); multiPart.release(branch, released);
        assertEquals(10, stock.get(branch, first).getAvailable());
        assertEquals(10, stock.get(branch, second).getAvailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.consume(branch, released));
        UUID consumed = UUID.randomUUID();
        multiPart.reserve(branch, consumed, items); multiPart.consume(branch, consumed); multiPart.consume(branch, consumed);
        assertEquals(8, stock.get(branch, first).getAvailable());
        assertEquals(7, stock.get(branch, second).getAvailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.release(branch, consumed));
    }

    @Test void multiPartReleaseRollsBackWhenOnePartWasAlreadyConsumed() {
        UUID branch = UUID.randomUUID(), operation = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("00000000-0000-4000-8000-000000000002");
        stock.create(branch, first, 5); stock.create(branch, second, 5);
        var items = java.util.List.of(new MultiPartReservationController.Item(first, 2), new MultiPartReservationController.Item(second, 2));
        multiPart.reserve(branch, operation, items);
        stock.consume(branch, second, operation);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.release(branch, operation));
        assertEquals(3, stock.get(branch, first).getAvailable());
        assertEquals(3, stock.get(branch, second).getAvailable());
        assertEquals(ReservationManifest.State.RESERVED, mongoTemplate.findById(branch + ":" + operation, ReservationManifest.class).state());
    }

    @Test void concurrentFinalizationsLeaveOneConsistentOutcome() throws Exception {
        UUID branch = UUID.randomUUID(), reservation = UUID.randomUUID();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        stock.create(branch, first, 5); stock.create(branch, second, 7);
        var items = java.util.List.of(new MultiPartReservationController.Item(first, 2), new MultiPartReservationController.Item(second, 3));
        multiPart.reserve(branch, reservation, items);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var release = pool.submit(() -> { start.await(); try { multiPart.release(branch, reservation); return true; } catch (RuntimeException e) { return false; } });
            var consume = pool.submit(() -> { start.await(); try { multiPart.consume(branch, reservation); return true; } catch (RuntimeException e) { return false; } });
            start.countDown();
            boolean released = release.get(30, java.util.concurrent.TimeUnit.SECONDS);
            boolean consumed = consume.get(30, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(released, consumed, "one transaction must win");
            var manifest = mongoTemplate.findById(branch + ":" + reservation, ReservationManifest.class);
            assertNotNull(manifest);
            assertEquals(released ? ReservationManifest.State.RELEASED : ReservationManifest.State.CONSUMED, manifest.state());
            assertEquals(released ? 5 : 3, stock.get(branch, first).getAvailable());
            assertEquals(released ? 7 : 4, stock.get(branch, second).getAvailable());
            if (released) {
                multiPart.release(branch, reservation);
                assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.consume(branch, reservation));
            } else {
                multiPart.consume(branch, reservation);
                assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> multiPart.release(branch, reservation));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void staleConcurrentReservationIsRejectedAndRetryDoesNotDoubleDeduct() {
        UUID branch = UUID.randomUUID(), part = UUID.randomUUID();
        UUID firstReservation = UUID.randomUUID(), secondReservation = UUID.randomUUID();
        stock.create(branch, part, 5);
        var first = stock.get(branch, part);
        var stale = stock.get(branch, part);
        first.reserve(firstReservation, 3);
        stale.reserve(secondReservation, 3);
        repository.save(first);
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> repository.save(stale));
        assertEquals(2, stock.get(branch, part).getAvailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> stock.reserve(branch, part, secondReservation, 3));
        assertEquals(2, stock.reserve(branch, part, firstReservation, 3).getAvailable());
        assertEquals(2, stock.get(branch, part).getAvailable());
    }

    @Test void identicalPartInAnotherBranchHasIndependentBalance() {
        UUID firstBranch = UUID.randomUUID(), secondBranch = UUID.randomUUID(), part = UUID.randomUUID();
        stock.create(firstBranch, part, 4);
        stock.create(secondBranch, part, 7);
        stock.reserve(firstBranch, part, UUID.randomUUID(), 3);
        assertEquals(1, stock.get(firstBranch, part).getAvailable());
        assertEquals(7, stock.get(secondBranch, part).getAvailable());
    }

    @Test void legacyReservationCanBeConsumedWithoutRestoringStock() {
        UUID branch = UUID.randomUUID(), part = UUID.randomUUID(), reservation = UUID.randomUUID();
        var oldReservation = new Document("quantity", 2).append("released", false);
        var reservations = new Document(reservation.toString(), oldReservation);
        mongoTemplate.getCollection("stock").insertOne(new Document("_id", StockDocument.key(branch, part))
                .append("branchId", branch.toString()).append("partId", part.toString()).append("available", 3)
                .append("reservations", reservations).append("version", 0L));
        assertEquals(3, stock.consume(branch, part, reservation).getAvailable());
        assertEquals(3, stock.consume(branch, part, reservation).getAvailable());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> stock.release(branch, part, reservation));
        assertEquals(3, stock.get(branch, part).getAvailable());
    }
}
