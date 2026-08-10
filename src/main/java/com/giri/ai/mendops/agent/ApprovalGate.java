package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.agent.audit.ApprovalAuditEntity;
import com.giri.ai.mendops.agent.audit.ApprovalAuditRepository;
import com.giri.ai.mendops.model.RemediationAction;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The guardrail in front of RemediationTools' risky actions. RemediationTools
 * calls propose() instead of running a risky action directly - this stores
 * it as pending and returns only an id/acknowledgement to the LLM. The real
 * action only runs when a human calls approve() via ApprovalController,
 * dispatched through RemediationActionExecutor.
 * <p>
 * PENDING and FAILED approvals can be (re)approved; APPROVED and REJECTED
 * are terminal. See PendingApproval.Status for why FAILED is retryable.
 * <p>
 * Crash-safe resumable: every propose/approve/reject/fail is written through
 * to ApprovalAuditRepository, and at startup {@link #loadFromRepository()}
 * reloads every persisted row back into the in-memory map. This works
 * because PendingApproval stores the action as DATA (actionType + params),
 * not a captured Callable - so a row that's still PENDING (or FAILED) after
 * a restart is genuinely re-approvable, not just visible as history. The
 * in-memory ConcurrentHashMap remains the operational source of truth for a
 * running instance (it's what approve()/reject() act on directly); the DB is
 * what makes that state survive a restart, not a separate best-effort audit
 * trail bolted on alongside it.
 * <p>
 * Audit writes ARE still non-fatal to the calling operation: if the DB is
 * down, propose()/approve()/reject() still work against the in-memory map
 * exactly as before - a real remediation that already ran successfully
 * should never be reported as "failed" to the caller just because the
 * write-through afterward couldn't reach the DB. The trade-off is narrower
 * than before, though: an approval proposed while the DB is unreachable
 * simply won't survive a restart, since there was never a persisted row to
 * reload it from.
 */
@Component
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    private final Map<String, PendingApproval> approvals = new ConcurrentHashMap<>();
    private final ApprovalAuditRepository auditRepository;
    private final RemediationActionExecutor executor;

    public ApprovalGate(ApprovalAuditRepository auditRepository, RemediationActionExecutor executor) {
        this.auditRepository = auditRepository;
        this.executor = executor;
    }

    /**
     * Reloads every persisted approval into the in-memory map. Runs once at
     * startup (after both constructor-injected dependencies are set), not
     * lazily - so a PENDING/FAILED approval from before a restart is visible
     * and approvable immediately, without waiting for someone to hit an
     * endpoint first.
     */
    @PostConstruct
    void loadFromRepository() {
        List<ApprovalAuditEntity> rows = auditRepository.findAll();
        for (ApprovalAuditEntity row : rows) {
            PendingApproval approval = PendingApproval.rehydrate(
                    row.getId(), row.getActionType(), row.getDescription(), row.getParams(),
                    row.getStatus(), row.getCreatedAt(), row.getResolvedAt(),
                    row.getExecutionResult(), row.getFailureReason());
            approvals.put(row.getId(), approval);
        }
        if (!rows.isEmpty()) {
            long resumable = rows.stream()
                    .filter(r -> r.getStatus() == PendingApproval.Status.PENDING
                            || r.getStatus() == PendingApproval.Status.FAILED)
                    .count();
            log.info("Reloaded {} approvals from persistence ({} still resumable)", rows.size(), resumable);
        }
    }

    /**
     * Records a risky action as pending instead of running it. Called from inside
     * a RemediationTools @Tool method. params must contain everything
     * RemediationActionExecutor needs to actually run actionType later - see
     * RemediationTools for what each gated action requires.
     */
    public String propose(RemediationAction.ActionType actionType, String description, Map<String, String> params) {
        String id = UUID.randomUUID().toString();
        PendingApproval approval = new PendingApproval(id, actionType, description, params);
        approvals.put(id, approval);
        log.info("Proposed action pending approval: id={} type={} description={}",
                id, actionType, description);

        writeAudit(() -> auditRepository.save(new ApprovalAuditEntity(
                id, actionType, description, params, PendingApproval.Status.PENDING, approval.createdAt())));

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
     * Runs the real action for a pending (or previously failed) approval, via
     * RemediationActionExecutor.execute(actionType, params) - not a captured
     * Callable, so this works identically whether the approval was proposed
     * moments ago or reloaded from a restart. Throws IllegalStateException if
     * it's already terminally resolved (approved or rejected). If execution
     * throws, the approval is marked FAILED with the failure reason captured
     * rather than silently staying PENDING - and the exception is rethrown so
     * the caller (e.g. ApprovalController) still sees the failure. A FAILED
     * approval can be retried by calling approve() again.
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
            String result = executor.execute(approval.actionType(), approval.params());
            approval.markApproved(result);
            writeAudit(() -> updateAudit(id, a -> {
                a.setStatus(PendingApproval.Status.APPROVED);
                a.setResolvedAt(approval.resolvedAt());
                a.setExecutionResult(result);
                a.setFailureReason(null);
            }));
            return result;
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            approval.markFailed(reason);
            log.warn("Execution failed for approval {}: {}", id, reason);
            writeAudit(() -> updateAudit(id, a -> {
                a.setStatus(PendingApproval.Status.FAILED);
                a.setResolvedAt(approval.resolvedAt());
                a.setFailureReason(reason);
            }));
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
        writeAudit(() -> updateAudit(id, a -> {
            a.setStatus(PendingApproval.Status.REJECTED);
            a.setResolvedAt(approval.resolvedAt());
        }));
    }

    private void updateAudit(String id, Consumer<ApprovalAuditEntity> mutator) {
        auditRepository.findById(id).ifPresentOrElse(entity -> {
            mutator.accept(entity);
            auditRepository.save(entity);
        }, () -> log.warn("No persisted row found for approval {} - propose()'s write must have failed earlier", id));
    }

    /**
     * Runs a persistence write and swallows (logs) any failure - see class
     * Javadoc on why this stays non-fatal to the calling operation.
     */
    private void writeAudit(Runnable write) {
        try {
            write.run();
        } catch (Exception e) {
            log.warn("Persistence write failed (approval flow continues unaffected): {}", e.getMessage());
        }
    }
}
