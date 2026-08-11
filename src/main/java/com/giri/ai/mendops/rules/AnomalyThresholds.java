package com.giri.ai.mendops.rules;

/**
 * Thresholds shared across multiple rules/components that need to agree on
 * what counts as anomalous - extracted specifically because OutboxLagRule,
 * HealthyStateRule, and IncidentTracker all need the EXACT same notion of
 * "lag is a problem" to stay consistent. Before this existed, the value was
 * duplicated independently in OutboxLagRule and HealthyStateRule; adding a
 * third independent copy for IncidentTracker was the point where letting
 * them drift out of sync stopped being a hypothetical risk.
 */
public final class AnomalyThresholds {

    public static final long OUTBOX_LAG_THRESHOLD_SECONDS = 120;
    public static final long DLQ_DEPTH_THRESHOLD = 50;

    private AnomalyThresholds() {
    }
}
