package com.oficina.execution;

import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/branches/{branch}/work-orders")
class WorkOrderController {
    record Diagnosis(@NotBlank @Size(max = 4000) String description) {}
    private final WorkOrderService service;
    private final org.springframework.beans.factory.ObjectProvider<WorkOrderAuthorization> authorization;
    WorkOrderController(WorkOrderService service, org.springframework.beans.factory.ObjectProvider<WorkOrderAuthorization> authorization) {
        this.service = service; this.authorization = authorization;
    }
    @PostMapping("/{order}") @ResponseStatus(HttpStatus.CREATED)
    WorkOrder create(@PathVariable UUID branch, @PathVariable UUID order) { return service.create(branch, order); }
    @PutMapping("/{order}")
    WorkOrder ensureExists(@PathVariable UUID branch, @PathVariable UUID order) { return service.ensureExists(branch, order); }
    @GetMapping("/{order}") WorkOrder get(@PathVariable UUID branch, @PathVariable UUID order) { return service.get(branch, order); }
    @GetMapping List<WorkOrder> queue(@PathVariable UUID branch, @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Limit must be between 1 and 100");
        return service.queue(branch, limit);
    }
    @PostMapping("/{order}/diagnosis/start") WorkOrder startDiagnosis(@PathVariable UUID branch, @PathVariable UUID order) {
        return service.update(branch, order, WorkOrder::startDiagnosis);
    }
    @PostMapping("/{order}/diagnosis/complete") WorkOrder completeDiagnosis(@PathVariable UUID branch, @PathVariable UUID order, @Valid @RequestBody Diagnosis request) {
        return service.update(branch, order, item -> item.completeDiagnosis(request.description()));
    }
    @PostMapping("/{order}/start") WorkOrder start(@PathVariable UUID branch, @PathVariable UUID order) {
        var service = authorization.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Stock integration is disabled");
        return service.start(branch, order);
    }
    @PostMapping("/{order}/complete") WorkOrder complete(@PathVariable UUID branch, @PathVariable UUID order) {
        return service.update(branch, order, WorkOrder::completeWork);
    }
}
