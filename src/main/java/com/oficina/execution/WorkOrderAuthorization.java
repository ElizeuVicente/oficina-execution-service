package com.oficina.execution;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.oficina.execution.MultiPartReservationController.Item;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Called only by the trusted orchestrator after payment confirmation. */
@org.springframework.stereotype.Service
@ConditionalOnProperty(name = "execution.multi-part.enabled", havingValue = "true")
class WorkOrderAuthorization {
    private final WorkOrderService orders;
    private final WorkOrderRepository repository;
    private final MultiPartReservationService reservations;
    WorkOrderAuthorization(WorkOrderService orders, WorkOrderRepository repository, MultiPartReservationService reservations) {
        this.orders = orders; this.repository = repository; this.reservations = reservations;
    }
    @Transactional(transactionManager = "mongoTransactionManager")
    public WorkOrder authorize(UUID branch, UUID order, UUID attempt, List<MultiPartReservationController.Item> items) {
        return authorize(branch, order, attempt, order, items, false);
    }
    @Transactional(transactionManager = "mongoTransactionManager")
    public WorkOrder authorize(UUID branch, UUID order, UUID attempt, UUID reservation, List<MultiPartReservationController.Item> items, boolean mustExist) {
        WorkOrder work = orders.get(branch, order);
        boolean progressed = work.getStatus() == WorkOrder.Status.IN_PROGRESS || work.getStatus() == WorkOrder.Status.COMPLETED;
        work.authorize(attempt, reservation);
        if (progressed) reservations.verifyExisting(branch, reservation, items);
        else if (mustExist) reservations.verifyReserved(branch, reservation, items);
        else reservations.reserve(branch, reservation, items);
        return repository.save(work);
    }
    @Transactional(transactionManager = "mongoTransactionManager")
    public WorkOrder start(UUID branch, UUID order) {
        WorkOrder work = orders.get(branch, order);
        work.startWork();
        reservations.consume(branch, work.reservationId());
        return repository.save(work);
    }
    @Transactional(transactionManager = "mongoTransactionManager")
    public WorkOrder cancel(UUID branch, UUID order) {
        return cancel(branch, order, order);
    }
    @Transactional(transactionManager = "mongoTransactionManager")
    public WorkOrder cancel(UUID branch, UUID order, UUID reservation) {
        WorkOrder work = orders.get(branch, order);
        work.bindCancellationReservation(reservation);
        work.cancelBeforeStart();
        reservations.release(branch, reservation);
        return repository.save(work);
    }
}

@RestController
@RequestMapping("/api/branches/{branch}/work-orders/{order}")
@ConditionalOnProperty(name = "execution.multi-part.enabled", havingValue = "true")
class WorkOrderAuthorizationController {
    record Authorization(@NotNull UUID paymentAttemptId, @NotNull UUID reservationId, @NotNull @Size(max = 100) List<@NotNull @Valid Item> items) { }
    private final WorkOrderAuthorization authorization;
    WorkOrderAuthorizationController(WorkOrderAuthorization authorization) { this.authorization = authorization; }
    @PutMapping("/authorization")
    WorkOrder authorize(@PathVariable UUID branch, @PathVariable UUID order, @Valid @RequestBody Authorization request) {
        return authorization.authorize(branch, order, request.paymentAttemptId(), request.reservationId(), request.items(), true);
    }
    @PostMapping("/authorization/cancel")
    WorkOrder cancel(@PathVariable UUID branch, @PathVariable UUID order, @RequestParam(required = false) UUID reservationId) {
        return authorization.cancel(branch, order, reservationId == null ? order : reservationId);
    }
}
