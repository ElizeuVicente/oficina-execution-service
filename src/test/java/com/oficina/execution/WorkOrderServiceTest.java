package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class WorkOrderServiceTest {
    @Test void retryReturnsExistingOrderWithoutReplacingItsProgress() {
        var repository = mock(WorkOrderRepository.class);
        var service = new WorkOrderService(repository);
        UUID branch = UUID.randomUUID(), order = UUID.randomUUID();
        var existing = new WorkOrder(branch, order, Instant.now());
        existing.startDiagnosis();
        when(repository.insert(any(WorkOrder.class))).thenThrow(new DuplicateKeyException("duplicate"));
        when(repository.findById(WorkOrder.key(branch, order))).thenReturn(Optional.of(existing));
        assertSame(existing, service.ensureExists(branch, order));
        verify(repository, never()).save(any(WorkOrder.class));
    }
}
