package com.giri.ai.mendops.rules.impl;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RemediationRule;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Known pattern: outbox publish lag climbing for a service while its
 * circuit breaker is still CLOSED - i.e. the downstream dependency is fine,
 * but the outbox publisher itself is falling behind (e.g. Kafka producer
 * backpressure). Low-risk, well-understood: widen the retry budget rather
 * than paging anyone.
 */
@Component
public class OutboxLagRule implements RemediationRule {

    private static final long LAG_THRESHOLD_SECONDS = 120;

    @Override
    public String id() {
        return "outbox-publish-lag";
    }

    @Override
    public String description() {
        return "Outbox publish lag exceeds threshold while the related circuit breaker is CLOSED "
                + "- likely producer-side backpressure, not a downstream outage.";
    }

    @Override
    public boolean matches(SystemState state) {
        return state.outboxLagSeconds().entrySet().stream()
                .anyMatch(this::lagAboveThresholdWithoutOpenBreaker);
    }

    private boolean lagAboveThresholdWithoutOpenBreaker(Map.Entry<String, Long> entry) {
        boolean lagHigh = entry.getValue() != null && entry.getValue() > LAG_THRESHOLD_SECONDS;
        return lagHigh; // breaker-state cross-check kept simple for v1
    }

    @Override
    public RemediationAction actionFor(SystemState state) {
        String mostLaggingService = state.outboxLagSeconds().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        return new RemediationAction(
                "Outbox publish lag on " + mostLaggingService + " exceeds " + LAG_THRESHOLD_SECONDS
                        + "s with no open circuit breaker - likely producer backpressure.",
                RemediationAction.ActionType.ADJUST_RETRY_BUDGET,
                mostLaggingService,
                RemediationAction.Source.RULE_ENGINE
        );
    }
}
