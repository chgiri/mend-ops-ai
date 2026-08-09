package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The guardrail in front of RemediationTools' risky actions. RemediationTools
 * calls propose() instead of running a risky action directly - this stores
 * it as pending and returns only an id/acknowledgement to the LLM. The real
 * action only runs when a human calls approve() via ApprovalController.
 * <p>
 * PENDING and FAILED approvals can be (re)approved; APPROVED and REJECTED
 * are terminal. See PendingApproval.Status for why FAILED is retryable.
 * <p>
 * v1: in-memory only (ConcurrentHashMap), lost on restart. Fine for local/demo
 * use; if this needs to survive a restart or be visible across instances,
 * that's the point to add JPA persistence (see project notes on where JPA
 * would actually earn its place in this project).
 */
@Component
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();

    /**
     * Records a risky action as pending instead of running it. Called from inside
     * a RemediationTools @Tool method - the Callable passed here is the *real*
     * action (Kafka replay, retry budget change, etc.), not executed until approved.
     */
    public String propose(RemediationAction.ActionType actionType, String description,
                           Callable<String> execution) {
        String id = UUID.randomUUID().toString();
        approvals.put(id, new PendingApproval(id, actionType, description, execution));
        log.info("Proposed action pending approval: id={} type={} description={}",
                id, actionType, description);
        return id;
    }

    public PendingApproval get(String id) {
        PendingApproval approval = approvals.get(id);
        if (approval == null) {
            throw new NoSuchElementException("No pending approval with id " + id);
        }
        return approval;
    }

    public List<PendingApproval> pending() {
        return approvals.values().stream()
                .filter(a -> a.status() == PendingApproval.Status.PENDING)
                .toList();
    }

    public Collection<PendingApproval> all() {
        return approvals.values();
    }

    /**
     * Runs the real action for a pending (or previously failed) approval.
     * Throws IllegalStateException if it's already terminally resolved
     * (approved or rejected). If the real action throws, the approval is
     * marked FAILED with the failure reason captured rather than silently
     * staying PENDING - and the exception is rethrown so the caller (e.g.
     * ApprovalController) still sees the failure. A FAILED approval can be
     * retried by calling approve() again, since the underlying cause (a
     * transient Kafka blip, a not-yet-deployed admin endpoint, etc.) may
     * since be resolved.
     */
    public String approve(String id) throws Exception {
        PendingApproval approval = get(id);
        if (approval.status() == PendingApproval.Status.APPROVED
                || approval.status() == PendingApproval.Status.REJECTED) {
            throw new IllegalStateException(
                    "Approval " + id + " already resolved with status " + approval.status());
        }

        log.info("Approving and executing action: id={} type={}", id, approval.actionType());
        try {
            String result = approval.runExecution();
            approval.markApproved(result);
            return result;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            approval.markFailed(reason);
            log.warn("Execution failed for approval {}: {}", id, reason);
            throw e;
        }
    }

    public void reject(String id) {
        PendingApproval approval = get(id);
        if (approval.status() == PendingApproval.Status.APPROVED
                || approval.status() == PendingApproval.Status.REJECTED) {
            throw new IllegalStateException(
                    "Approval " + id + " already resolved with status " + approval.status());
        }

        log.info("Rejecting action: id={} type={}", id, approval.actionType());
        approval.markRejected();
    }
}
