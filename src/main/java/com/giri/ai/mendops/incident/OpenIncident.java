package com.giri.ai.mendops.incident;

import java.time.Instant;

/**
 * A single atomic anomalous fact (e.g. "circuitBreaker:customerClient:HALF_OPEN")
 * that's currently present. Tracked independently per fact - see
 * IncidentTracker's Javadoc for why the whole SystemState snapshot is
 * deliberately NOT treated as one combined signature.
 */
public class OpenIncident {

    private final String fact;
    private final Instant firstSeenAt;
    private volatile Instant lastSeenAt;

    OpenIncident(String fact, Instant firstSeenAt) {
        this.fact = fact;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = firstSeenAt;
    }

    void touch(Instant now) {
        this.lastSeenAt = now;
    }

    public String fact() {
        return fact;
    }

    public Instant firstSeenAt() {
        return firstSeenAt;
    }

    public Instant lastSeenAt() {
        return lastSeenAt;
    }
}
