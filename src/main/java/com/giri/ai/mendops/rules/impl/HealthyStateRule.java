package com.giri.ai.mendops.rules.impl;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RemediationRule;
import org.springframework.stereotype.Component;

/**
 * Catches the steady/nothing-wrong state: no circuit breaker OPEN and no
 * outbox lag above OutboxLagRule's threshold.
 * <p>
 * Exists specifically because SystemStatePoller now runs continuously on a
 * schedule (unlike the original on-demand demo endpoints) - without this
 * rule, every poll of a perfectly healthy system falls through to
 * EscalationService and burns an LLM call for no reason. Whenever this
 * rule matches, no anomaly rule would have matched either, so its position
 * in the rule list relative to the others doesn't matter.
 */
@Component
public class HealthyStateRule implements RemediationRule {

    private static final long LAG_THRESHOLD_SECONDS = 120;

    @Override
    public String id() {
        return "healthy-state";
    }

    @Override
    public String description() {
        return "No circuit breaker is OPEN and no outbox lag exceeds threshold - nothing to do.";
    }

    @Override
    public boolean matches(SystemState state) {
        boolean anyBreakerOpen = state.circuitBreakers().values().stream()
                .anyMatch(cb -> cb == SystemState.CircuitBreakerState.OPEN);

        boolean anyLagHigh = state.outboxLagSeconds().values().stream()
                .anyMatch(lag -> lag != null && lag > LAG_THRESHOLD_SECONDS);

        return !anyBreakerOpen && !anyLagHigh;
    }

    @Override
    public RemediationAction actionFor(SystemState state) {
        return new RemediationAction(
                "System healthy - no circuit breakers open, no significant outbox lag.",
                RemediationAction.ActionType.NO_ACTION,
                "none",
                RemediationAction.Source.RULE_ENGINE
        );
    }
}
