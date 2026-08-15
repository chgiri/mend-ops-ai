package com.giri.ai.mendops.rules.impl;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.AnomalyThresholds;
import com.giri.ai.mendops.rules.RemediationRule;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Known pattern: outbox publish lag climbing for a service while its
 * circuit breaker is still CLOSED - i.e. the downstream dependency is fine,
 * but the outbox publisher itself is falling behind (e.g. Kafka producer
 * backpressure).
 * <p>
 * Pages rather than auto-remediating. An earlier version of this rule used
 * ADJUST_RETRY_BUDGET, on the assumption that "widen the retry budget" was a
 * low-risk fix - but the only real ADJUST_RETRY_BUDGET integration
 * (RetryBudgetAdminClient) tunes oms-main's Resilience4j HTTP retry config
 * for calls TO product-service/customer-service (targets: "productClient"/
 * "customerClient"). That's a completely different subsystem from outbox
 * publishing to Kafka - mostLaggingService here is an outbox source name
 * (e.g. "shipment-service"), which is never a valid Resilience4j instance
 * name. Executing ADJUST_RETRY_BUDGET for this diagnosis wouldn't just fail
 * to help - even if the naming were fixed, it would silently tune an
 * unrelated HTTP client while doing nothing about the actual outbox lag. No
 * real remediation for outbox-publisher backpressure exists yet (would mean
 * a new integration - tuning Kafka producer retry/backoff or the outbox
 * poll batch size, neither of which RetryBudgetAdminClient does) - PAGE_ONCALL
 * is the honest choice until one does. See RuleCandidateDraftingService's
 * system prompt for the same constraint applied to the LLM-drafted path -
 * an OUTBOX_LAG-kind fact should never be drafted as ADJUST_RETRY_BUDGET
 * either, for the identical reason.
 */
@Component
public class OutboxLagRule implements RemediationRule {

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
        boolean lagHigh = entry.getValue() != null && entry.getValue() > AnomalyThresholds.OUTBOX_LAG_THRESHOLD_SECONDS;
        return lagHigh; // breaker-state cross-check kept simple for v1
    }

    @Override
    public RemediationAction actionFor(SystemState state) {
        String mostLaggingService = state.outboxLagSeconds().entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");

        String diagnosis = "Outbox publish lag on " + mostLaggingService + " exceeds "
                + AnomalyThresholds.OUTBOX_LAG_THRESHOLD_SECONDS
                + "s with no open circuit breaker - likely producer backpressure. No automated "
                + "remediation exists for outbox-publisher backpressure yet - needs a human look.";

        return new RemediationAction(
                diagnosis,
                RemediationAction.ActionType.PAGE_ONCALL,
                mostLaggingService,
                RemediationAction.Source.RULE_ENGINE,
                Map.of("summary", diagnosis)
        );
    }
}
