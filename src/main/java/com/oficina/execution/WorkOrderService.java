package com.oficina.execution;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class WorkOrderService {
    private final WorkOrderRepository repository;
    WorkOrderService(WorkOrderRepository repository) { this.repository = repository; }
    WorkOrder create(UUID branch, UUID order) {
        try { return repository.insert(new WorkOrder(branch, order, Instant.now())); }
        catch (DuplicateKeyException e) { throw new ResponseStatusException(HttpStatus.CONFLICT, "Work order already exists", e); }
    }
    WorkOrder ensureExists(UUID branch, UUID order) {
        try { return repository.insert(new WorkOrder(branch, order, Instant.now())); }
        catch (DuplicateKeyException e) { return get(branch, order); }
    }
    WorkOrder get(UUID branch, UUID order) {
        return repository.findById(WorkOrder.key(branch, order)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    List<WorkOrder> queue(UUID branch, int limit) {
        return repository.findByBranchIdAndStatusInOrderByCreatedAtAscIdAsc(branch.toString(),
                List.of(WorkOrder.Status.AWAITING_DIAGNOSIS, WorkOrder.Status.DIAGNOSING,
                        WorkOrder.Status.AWAITING_PAYMENT, WorkOrder.Status.READY, WorkOrder.Status.IN_PROGRESS),
                PageRequest.of(0, limit));
    }
    WorkOrder update(UUID branch, UUID order, Consumer<WorkOrder> operation) {
        WorkOrder item = get(branch, order);
        try { operation.accept(item); return repository.save(item); }
        catch (IllegalStateException | IllegalArgumentException | OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }
}
