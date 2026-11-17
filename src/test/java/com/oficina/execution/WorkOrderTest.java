package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WorkOrderTest {
    @Test void diagnosisAndExecutionFollowValidSequence() {
        var order = new WorkOrder(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        assertThrows(IllegalStateException.class, order::startWork);
        order.startDiagnosis(); order.startDiagnosis();
        assertThrows(IllegalArgumentException.class, () -> order.completeDiagnosis(" "));
        order.completeDiagnosis("Troca de freios"); order.completeDiagnosis("Troca de freios");
        assertThrows(IllegalStateException.class, () -> order.completeDiagnosis("Outro diagnóstico"));
        assertThrows(IllegalStateException.class, order::startWork);
        order.markReady(); order.startWork(); order.completeWork(); order.completeWork();
        assertEquals(WorkOrder.Status.COMPLETED, order.getStatus());
        assertThrows(IllegalStateException.class, order::startWork);
    }
    @Test void sameOrderInDifferentBranchesHasDistinctIdentity() {
        var orderId = UUID.randomUUID();
        assertNotEquals(WorkOrder.key(UUID.randomUUID(), orderId), WorkOrder.key(UUID.randomUUID(), orderId));
    }
}
