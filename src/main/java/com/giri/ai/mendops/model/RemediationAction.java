package com.giri.ai.mendops.model;

import java.util.Map;

/**
 * The outcome of evaluating a SystemState: a human-readable diagnosis and
 * the concrete remediation to apply. Produced by either a matched
 * RemediationRule or, when nothing matches, by the LLM escalation path.
 * <p>
 * actionParams carries everything RemediationActionExecutor needs to
 * actually run actionType (e.g. {"serviceName":..., "maxAttempts":...} for
 * ADJUST_RETRY_BUDGET, {"summary":...} for PAGE_ONCALL) - added specifically
 * so AgentOrchestrator can dispatch a real action for a rule match, not just
 * log it. targetService stays as a separate human-readable field for
 * display/logging even though it's usually also present inside
 * actionParams under a more specific key.
 */
public record RemediationAction(
        String diagnosis,
        ActionType actionType,
        String targetService,
        Source source,
        Map<String, String> actionParams
) {

    public enum ActionType {
        REPLAY_DLQ_BATCH,
        ADJUST_RETRY_BUDGET,
        PAGE_ONCALL,
        NO_ACTION
    }

    /** Where this decision came from - important for the coverage metric and audit trail. */
    public enum Source {
        RULE_ENGINE,
        LLM_ESCALATION
    }
}
