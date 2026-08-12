package com.giri.ai.mendops.rulecandidate;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RemediationRule;

import java.util.Map;
import java.util.Objects;

/**
 * The single generic RemediationRule implementation that interprets a
 * RuleCandidate's structured conditions against a SystemState - see
 * RuleCandidate's Javadoc for why candidates are data, not generated code.
 * One DataDrivenRule instance wraps exactly one RuleCandidate; RuleEngine
 * holds these in its liveDynamicRules/shadowRules lists depending on the
 * candidate's status at the time it was added (see
 * RuleCandidateReviewService, which constructs and registers these).
 * <p>
 * id() returns the candidate's own id directly (a UUID) - no prefix needed,
 * since it can't collide with the static rules' short hand-picked ids
 * (e.g. "healthy-state") and stays directly traceable back to
 * RuleCandidateController's view of the same candidate.
 */
public class DataDrivenRule implements RemediationRule {

    private final RuleCandidate candidate;

    public DataDrivenRule(RuleCandidate candidate) {
        this.candidate = candidate;
    }

    @Override
    public String id() {
        return candidate.id();
    }

    @Override
    public String description() {
        return candidate.diagnosis() != null
                ? candidate.diagnosis()
                : "Promoted rule candidate for " + candidate.sourceFact();
    }

    @Override
    public boolean matches(SystemState state) {
        return candidate.conditions().stream().allMatch(condition -> evaluate(condition, state));
    }

    @Override
    public RemediationAction actionFor(SystemState state) {
        return new RemediationAction(
                candidate.diagnosis() != null
                        ? candidate.diagnosis() : "Promoted rule matched: " + candidate.sourceFact(),
                candidate.actionType(),
                targetFrom(candidate.actionType(), candidate.actionParams()),
                RemediationAction.Source.RULE_ENGINE,
                candidate.actionParams()
        );
    }

    private boolean evaluate(RuleCandidate.Condition condition, SystemState state) {
        String[] parts = condition.field().split("\\.", 2);
        if (parts.length != 2) {
            return false;
        }
        String mapName = parts[0];
        String key = parts[1];

        return switch (mapName) {
            case "circuitBreakers" -> evaluateCircuitBreaker(state.circuitBreakers().get(key), condition);
            case "outboxLagSeconds" -> evaluateNumeric(state.outboxLagSeconds().get(key), condition);
            case "dlqDepth" -> evaluateNumeric(state.dlqDepth().get(key), condition);
            default -> false;
        };
    }

    private boolean evaluateCircuitBreaker(SystemState.CircuitBreakerState actual, RuleCandidate.Condition condition) {
        String actualName = actual != null ? actual.name() : null;
        return switch (condition.operator()) {
            case EQUALS -> Objects.equals(actualName, condition.value());
            case NOT_EQUALS -> !Objects.equals(actualName, condition.value());
            // GREATER_THAN/LESS_THAN aren't meaningful against an enum state - a
            // candidate condition combining these is malformed, treated as a non-match
            // rather than throwing, since this runs on every poll cycle.
            case GREATER_THAN, LESS_THAN -> false;
        };
    }

    private boolean evaluateNumeric(Long actual, RuleCandidate.Condition condition) {
        if (actual == null) {
            return false;
        }
        long threshold;
        try {
            threshold = Long.parseLong(condition.value());
        } catch (NumberFormatException e) {
            return false;
        }
        return switch (condition.operator()) {
            case GREATER_THAN -> actual > threshold;
            case LESS_THAN -> actual < threshold;
            case EQUALS -> actual == threshold;
            case NOT_EQUALS -> actual != threshold;
        };
    }

    private String targetFrom(RemediationAction.ActionType actionType, Map<String, String> params) {
        return switch (actionType) {
            case ADJUST_RETRY_BUDGET -> params.getOrDefault("serviceName", "unknown");
            case REPLAY_DLQ_BATCH -> params.getOrDefault("topic", "unknown");
            case PAGE_ONCALL, NO_ACTION -> "none";
        };
    }
}
