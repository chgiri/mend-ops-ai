package com.giri.ai.mendops.model;

/**
 * The outcome of evaluating a SystemState: a human-readable diagnosis and
 * the concrete remediation to apply. Produced by either a matched
 * RemediationRule or, when nothing matches, by the LLM escalation path.
 */
public record RemediationAction(
        String diagnosis,
        ActionType actionType,
        String targetService,
        Source source
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
