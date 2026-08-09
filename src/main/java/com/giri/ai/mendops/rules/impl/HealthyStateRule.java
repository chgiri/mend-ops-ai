package com.giri.ai.mendops.rules.impl;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RemediationRule;
import org.springframework.stereotype.Component;

/**
 * Catches the steady/nothing-wrong state: every circuit breaker CLOSED and
 * no outbox lag above OutboxLagRule's threshold.
 * <p>
 * Exists specifically because SystemStatePoller now runs continuously on a
 * schedule (unlike the original on-demand demo endpoints) - without this
 * rule, every poll of a perfectly healthy system falls through to
 * EscalationService and burns an LLM call for no reason.
 * <p>
 * Deliberately requires CLOSED specifically, not just "not OPEN" - a
 * HALF_OPEN breaker is a genuine transitional/degraded state (the breaker
 * is actively testing recovery), not a healthy one, and no other rule in
 * this project currently treats HALF_OPEN as an anomaly either. Treating it
 * as "healthy" here would silently swallow it instead of letting it fall
 * through to LLM escalation - which is exactly what happened before this
 * fix (a HALF_OPEN-based demo scenario was being incorrectly resolved as
 * healthy by this rule). Whenever this rule matches, no anomaly rule would
 * have matched either, so its position in the rule list relative to the
 * others doesn't matter.
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
        return "Every circuit breaker is CLOSED and no outbox lag exceeds threshold - nothing to do.";
    }

    @Override
    public boolean matches(SystemState state) {
        boolean allBreakersClosed = state.circuitBreakers().values().stream()
                .allMatch(cb -> cb == SystemState.CircuitBreakerState.CLOSED);

        boolean anyLagHigh = state.outboxLagSeconds().values().stream()
                .anyMatch(lag -> lag != null && lag > LAG_THRESHOLD_SECONDS);

        return allBreakersClosed && !anyLagHigh;
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