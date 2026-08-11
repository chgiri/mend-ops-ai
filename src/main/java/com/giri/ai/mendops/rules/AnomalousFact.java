package com.giri.ai.mendops.rules;

import java.util.Optional;

/**
 * A single atomic anomalous condition, and the one place that knows how to
 * format/parse its String representation (e.g.
 * "circuitBreaker:customerClient:HALF_OPEN", "outboxLag:shipment-service").
 * IncidentTracker produces these (as Strings, for its Map keys) and
 * RuleCandidateDraftingService parses them back - keeping the format defined
 * in exactly one place instead of duplicated string-building/parsing logic
 * that could drift apart.
 * <p>
 * value is only meaningful for CIRCUIT_BREAKER (the exact breaker state,
 * e.g. "HALF_OPEN") - OUTBOX_LAG/DLQ_DEPTH facts deliberately don't embed
 * their exact numeric value (an exact lag of 45s vs 52s shouldn't be treated
 * as a "different" recurring fact), so value is null for those.
 */
public record AnomalousFact(Kind kind, String target, String value) {

    public enum Kind {
        CIRCUIT_BREAKER, OUTBOX_LAG, DLQ_DEPTH
    }

    public String toFactString() {
        return switch (kind) {
            case CIRCUIT_BREAKER -> "circuitBreaker:" + target + ":" + value;
            case OUTBOX_LAG -> "outboxLag:" + target;
            case DLQ_DEPTH -> "dlqDepth:" + target;
        };
    }

    public static Optional<AnomalousFact> parse(String factString) {
        String[] parts = factString.split(":", 3);
        if (parts.length < 2) {
            return Optional.empty();
        }
        return switch (parts[0]) {
            case "circuitBreaker" -> parts.length == 3
                    ? Optional.of(new AnomalousFact(Kind.CIRCUIT_BREAKER, parts[1], parts[2]))
                    : Optional.empty();
            case "outboxLag" -> Optional.of(new AnomalousFact(Kind.OUTBOX_LAG, parts[1], null));
            case "dlqDepth" -> Optional.of(new AnomalousFact(Kind.DLQ_DEPTH, parts[1], null));
            default -> Optional.empty();
        };
    }
}
