package com.oficina.execution;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

interface WorkOrderRepository extends MongoRepository<WorkOrder, String> {
    List<WorkOrder> findByBranchIdAndStatusInOrderByCreatedAtAscIdAsc(String branchId, Collection<WorkOrder.Status> statuses, Pageable page);
}
