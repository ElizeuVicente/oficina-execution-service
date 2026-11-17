package com.oficina.execution;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("work_orders")
public class WorkOrder {
    public enum Status { AWAITING_DIAGNOSIS, DIAGNOSING, AWAITING_PAYMENT, READY, IN_PROGRESS, COMPLETED, CANCELLED }
    @Id private String id;
    private String branchId;
    private String orderId;
    private Instant createdAt;
    private String diagnosis;
    private Status status;
    private String paymentAttemptId;
    private String reservationId;
    @Version private Long version;

    protected WorkOrder() {}
    WorkOrder(UUID branch, UUID order, Instant now) {
        this.id = key(branch, order);
        this.branchId = branch.toString();
        this.orderId = order.toString();
        this.createdAt = now;
        this.status = Status.AWAITING_DIAGNOSIS;
    }
    static String key(UUID branch, UUID order) { return branch + ":" + order; }
    void startDiagnosis() { transition(Status.AWAITING_DIAGNOSIS, Status.DIAGNOSING); }
    void completeDiagnosis(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Diagnosis is required");
        if (status == Status.AWAITING_PAYMENT && text.equals(diagnosis)) return;
        if (status != Status.DIAGNOSING) throw new IllegalStateException("Diagnosis is not in progress");
        diagnosis = text;
        status = Status.AWAITING_PAYMENT;
    }
    void startWork() { transition(Status.READY, Status.IN_PROGRESS); }
    void completeWork() { transition(Status.IN_PROGRESS, Status.COMPLETED); }
    void markReady() { transition(Status.AWAITING_PAYMENT, Status.READY); }
    void authorize(UUID attempt) { authorize(attempt, UUID.fromString(orderId)); }
    void authorize(UUID attempt, UUID reservation) {
        if (attempt == null) throw new IllegalArgumentException("Payment attempt is required");
        if (reservation == null) throw new IllegalArgumentException("Reservation is required");
        if (paymentAttemptId != null) {
            if (!paymentAttemptId.equals(attempt.toString()) || !reservation.toString().equals(reservationId)) throw new IllegalStateException("Payment or reservation conflict");
            if (status == Status.READY || status == Status.IN_PROGRESS || status == Status.COMPLETED) return;
            throw new IllegalStateException("Order cannot be reauthorized");
        }
        markReady();
        paymentAttemptId = attempt.toString();
        reservationId = reservation.toString();
    }
    UUID reservationId() { return reservationId == null ? UUID.fromString(orderId) : UUID.fromString(reservationId); }
    void bindCancellationReservation(UUID reservation) {
        if (reservationId != null && !reservationId.equals(reservation.toString())) throw new IllegalStateException("Reservation conflict");
        reservationId = reservation.toString();
    }
    void cancelBeforeStart() {
        if (status == Status.AWAITING_PAYMENT) { status = Status.CANCELLED; return; }
        transition(Status.READY, Status.CANCELLED);
    }
    private void transition(Status expected, Status next) {
        if (status == next) return;
        if (status != expected) throw new IllegalStateException("Invalid transition: " + status + " -> " + next);
        status = next;
    }
    public String getBranchId() { return branchId; }
    public String getOrderId() { return orderId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDiagnosis() { return diagnosis; }
    public Status getStatus() { return status; }
    public Long getVersion() { return version; }
}
