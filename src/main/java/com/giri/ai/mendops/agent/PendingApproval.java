package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;

import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * A risky remediation the LLM proposed but hasn't been allowed to run yet.
 * The actual side-effecting logic is captured in {@code execution} at
 * proposal time and only invoked by {@link ApprovalGate#approve} - nothing
 * about the real action happens until a human explicitly approves this.
 * <p>
 * Mutable by design (status/resolvedAt/executionResult/failureReason change
 * in place) so ApprovalGate can hold a single instance per id rather than
 * replacing map entries - this class is not exposed directly over REST; see
 * ApprovalController's view DTO, since {@code execution} isn't serializable.
 */
public class PendingApproval {

    /**
     * FAILED means execution was attempted and threw - distinct from PENDING
     * (never attempted). FAILED is retryable: ApprovalGate.approve() allows
     * calling approve() again on a FAILED approval, since the underlying
     * cause (e.g. a transient Kafka blip, or an admin endpoint that wasn't
     * deployed yet) may since be resolved. APPROVED/REJECTED are terminal.
     */
    public enum Status {
        PENDING, APPROVED, REJECTED, FAILED
    }

    private final String id;
    private final Instant createdAt;
    private final RemediationAction.ActionType actionType;
    private final String description;
    private final Callable<String> execution;

    private volatile Status status = Status.PENDING;
    private volatile Instant resolvedAt;
    private volatile String executionResult;
    private volatile String failureReason;

    public PendingApproval(String id, RemediationAction.ActionType actionType, String description,
                            Callable<String> execution) {
        this.id = id;
        this.createdAt = Instant.now();
        this.actionType = actionType;
        this.description = description;
        this.execution = execution;
    }

    String runExecution() throws Exception {
        return execution.call();
    }

    void markApproved(String result) {
        this.status = Status.APPROVED;
        this.resolvedAt = Instant.now();
        this.executionResult = result;
        this.failureReason = null;
    }

    void markRejected() {
        this.status = Status.REJECTED;
        this.resolvedAt = Instant.now();
    }

    void markFailed(String reason) {
        this.status = Status.FAILED;
        this.resolvedAt = Instant.now();
        this.failureReason = reason;
    }

    public String id() {
        return id;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public RemediationAction.ActionType actionType() {
        return actionType;
    }

    public String description() {
        return description;
    }

    public Status status() {
        return status;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }

    public String executionResult() {
        return executionResult;
    }

    public String failureReason() {
        return failureReason;
    }
}
