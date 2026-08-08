package com.giri.ai.mendops.model;

import java.time.Instant;
import java.util.Map;

/**
 * A point-in-time snapshot of OMS telemetry the agent reasons over.
 * <p>
 * v1 keeps this intentionally flat and simple: a handful of named signals
 * per service, pulled from Resilience4j (circuit breakers), the outbox
 * table (publish lag), and Kafka (DLQ depth). Extend this as new signal
 * sources are wired in - Prometheus query results, Loki error rates, etc.
 */
public record SystemState(
        Instant capturedAt,
        Map<String, CircuitBreakerState> circuitBreakers,
        Map<String, Long> outboxLagSeconds,
        Map<String, Long> dlqDepth
) {

    public enum CircuitBreakerState {
        CLOSED, OPEN, HALF_OPEN
    }
}
