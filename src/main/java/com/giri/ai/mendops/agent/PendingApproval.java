package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;

import java.time.Instant;
import java.util.Map;

/**
 * A risky remediation the LLM proposed but hasn't been allowed to run yet.
 * <p>
 * Deliberately holds the action as DATA (actionType + a flat String/String
 * params map), not as a captured Callable/closure - that's what makes this
 * crash-safe resumable: ApprovalGate can persist this object's fields as-is,
 * reload them after a restart, and re-derive the real call via
 * RemediationActionExecutor.execute(actionType, params) at approval time,
 * whenever that happens to be. An earlier version of this class captured a
 * Callable<String> instead - simpler, but meant a PENDING approval was
 * silently unresumable after any restart, since a closure over live beans
 * can't be serialized. See RemediationActionExecutor's Javadoc for the
 * dispatch side of this.
 * <p>
 * Mutable by design (status/resolvedAt/executionResult/failureReason/createdAt
 * change in place) so ApprovalGate can hold a single instance per id rather
 * than replacing map entries - this class is not exposed directly over REST;
 * see ApprovalController's view DTO.
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
    private final RemediationAction.ActionType actionType;
    private final String description;
    private final Map<String, String> params;

    private volatile Instant createdAt = Instant.now();
    private volatile Status status = Status.PENDING;
    private volatile Instant resolvedAt;
    private volatile String executionResult;
    private volatile String failureReason;

    public PendingApproval(String id, RemediationAction.ActionType actionType, String description,
                            Map<String, String> params) {
        this.id = id;
        this.actionType = actionType;
        this.description = description;
        this.params = params;
    }

    /**
     * Rebuilds a PendingApproval from a persisted row (ApprovalGate does this
     * for every row at startup) - unlike the public constructor, this
     * reflects exactly what was stored rather than treating the approval as
     * newly proposed.
     */
    static PendingApproval rehydrate(String id, RemediationAction.ActionType actionType, String description,
                                      Map<String, String> params, Status status, Instant createdAt,
                                      Instant resolvedAt, String executionResult, String failureReason) {
        PendingApproval approval = new PendingApproval(id, actionType, description, params);
        approval.createdAt = createdAt;
        approval.status = status;
        approval.resolvedAt = resolvedAt;
        approval.executionResult = executionResult;
        approval.failureReason = failureReason;
        return approval;
    }

    Map<String, String> params() {
        return params;
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
