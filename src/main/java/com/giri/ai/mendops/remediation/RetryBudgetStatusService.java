package com.giri.ai.mendops.remediation;

import com.giri.ai.mendops.agent.PendingApproval;
import com.giri.ai.mendops.agent.audit.ApprovalAuditEntity;
import com.giri.ai.mendops.agent.audit.ApprovalAuditRepository;
import com.giri.ai.mendops.model.RemediationAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers "is any retry budget still widened from an old incident and never
 * put back?" - the gap flagged in the README ("no revert/expiry for a
 * temporarily widened retry budget").
 * <p>
 * Deliberately adds no new persistence: "current" is derived from the most
 * recently APPROVED adjustRetryBudget row already sitting in
 * ApprovalAuditRepository (ApprovalGate already writes one there on every
 * successful execution - see its Javadoc), and "default" is this app's own
 * config for what normal looks like (mendops.remediation.retry-budget.
 * default-max-attempts.*, in RemediationProperties). This is read-only and
 * reuses existing state rather than tracking a separate "previous value" on
 * every change - the full approval history already IS that record.
 */
@Component
public class RetryBudgetStatusService {

    private final RemediationProperties properties;
    private final ApprovalAuditRepository auditRepository;

    public RetryBudgetStatusService(RemediationProperties properties, ApprovalAuditRepository auditRepository) {
        this.properties = properties;
        this.auditRepository = auditRepository;
    }

    public List<RetryBudgetStatus> currentStatus() {
        Map<String, Integer> defaults = (properties.retryBudget() == null
                || properties.retryBudget().defaultMaxAttempts() == null)
                ? Map.of() : properties.retryBudget().defaultMaxAttempts();
        Set<String> knownAdminUrls = (properties.retryBudget() == null
                || properties.retryBudget().adminBaseUrl() == null)
                ? Set.of() : properties.retryBudget().adminBaseUrl().keySet();

        // Union of both configs, not just one - a service with an admin URL but no configured
        // default (or vice versa) is itself a real gap worth surfacing, not silently dropping.
        Set<String> serviceNames = new LinkedHashSet<>(knownAdminUrls);
        serviceNames.addAll(defaults.keySet());

        Map<String, ApprovalAuditEntity> mostRecentApprovedByService = mostRecentApprovedByService();

        List<RetryBudgetStatus> result = new ArrayList<>();
        for (String serviceName : serviceNames) {
            Integer defaultValue = defaults.get(serviceName);

            ApprovalAuditEntity mostRecent = mostRecentApprovedByService.get(serviceName);
            Integer currentValue = mostRecent == null ? null : parseMaxAttempts(mostRecent);

            boolean nonDefault = defaultValue != null && currentValue != null
                    && !defaultValue.equals(currentValue);

            result.add(new RetryBudgetStatus(
                    serviceName, defaultValue, currentValue, nonDefault,
                    mostRecent == null ? null : mostRecent.getResolvedAt()));
        }
        return result;
    }

    /**
     * findByActionTypeAndStatusOrderByResolvedAtDesc returns every approved
     * adjustRetryBudget, newest first - putIfAbsent here keeps only the first
     * (i.e. most recent) row seen per serviceName.
     */
    private Map<String, ApprovalAuditEntity> mostRecentApprovedByService() {
        Map<String, ApprovalAuditEntity> result = new LinkedHashMap<>();
        List<ApprovalAuditEntity> approvedHistory = auditRepository.findByActionTypeAndStatusOrderByResolvedAtDesc(
                RemediationAction.ActionType.ADJUST_RETRY_BUDGET, PendingApproval.Status.APPROVED);
        for (ApprovalAuditEntity entity : approvedHistory) {
            String serviceName = entity.getParams().get("serviceName");
            if (serviceName != null) {
                result.putIfAbsent(serviceName, entity);
            }
        }
        return result;
    }

    private Integer parseMaxAttempts(ApprovalAuditEntity entity) {
        String raw = entity.getParams().get("maxAttempts");
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
