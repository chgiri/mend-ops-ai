package com.giri.ai.mendops.agent.audit;

import com.giri.ai.mendops.agent.PendingApproval;
import com.giri.ai.mendops.model.RemediationAction;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Durable persistence for a PendingApproval - written by ApprovalGate at
 * every state transition (propose/approve/reject/fail) and reloaded in full
 * at startup, so approvals are genuinely crash-safe resumable, not just
 * audit history: a still-PENDING (or FAILED) row surviving a restart carries
 * everything RemediationActionExecutor needs (actionType + params) to
 * actually run it once approved, with no dependency on the process that
 * originally proposed it.
 * <p>
 * This only works because PendingApproval stores the action as DATA
 * (actionType + a flat String/String params map) rather than a captured
 * Callable/closure - see PendingApproval's Javadoc for why that change was
 * made. params uses a plain JPA @ElementCollection (a small side table,
 * approval_audit_params) rather than a JSON column, so this needs no extra
 * library beyond what spring-boot-starter-data-jpa already brings.
 */
@Entity
@Table(name = "approval_audit")
public class ApprovalAuditEntity {

    @Id
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemediationAction.ActionType actionType;

    @Lob
    @Column(nullable = false)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "approval_audit_params", joinColumns = @JoinColumn(name = "approval_id"))
    @MapKeyColumn(name = "param_key")
    @Column(name = "param_value")
    private Map<String, String> params = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PendingApproval.Status status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    @Lob
    private String executionResult;

    @Lob
    private String failureReason;

    /** JPA requires a no-arg constructor - not for application use. */
    protected ApprovalAuditEntity() {
    }

    public ApprovalAuditEntity(String id, RemediationAction.ActionType actionType, String description,
                                Map<String, String> params, PendingApproval.Status status, Instant createdAt) {
        this.id = id;
        this.actionType = actionType;
        this.description = description;
        this.params = params == null ? new HashMap<>() : new HashMap<>(params);
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public RemediationAction.ActionType getActionType() {
        return actionType;
    }

    public String getDescription() {
        return description;
    }

    public Map<String, String> getParams() {
        return params;
    }

    public PendingApproval.Status getStatus() {
        return status;
    }

    public void setStatus(PendingApproval.Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getExecutionResult() {
        return executionResult;
    }

    public void setExecutionResult(String executionResult) {
        this.executionResult = executionResult;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }
}
