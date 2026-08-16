package com.giri.ai.mendops.agent.audit;

import com.giri.ai.mendops.agent.PendingApproval;
import com.giri.ai.mendops.model.RemediationAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalAuditRepository extends JpaRepository<ApprovalAuditEntity, String> {

    List<ApprovalAuditEntity> findAllByOrderByCreatedAtDesc();

    /**
     * Used by RetryBudgetStatusService to find each service's most recently
     * approved maxAttempts value - the first entry per distinct
     * params.serviceName in this list (already ordered newest-first) is the
     * current value.
     */
    List<ApprovalAuditEntity> findByActionTypeAndStatusOrderByResolvedAtDesc(
            RemediationAction.ActionType actionType, PendingApproval.Status status);
}
