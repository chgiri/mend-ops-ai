package com.giri.ai.mendops.incident;

import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.AnomalyThresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks recurrence of anomalous conditions across SystemStatePoller's poll
 * cycles - the foundation the rule-promotion flow's "draft a candidate rule
 * once this pattern has recurred N times" trigger will be built on (not yet
 * implemented; this class only tracks and counts so far).
 * <p>
 * Deliberately per-fact, not per-snapshot: a SystemState can have multiple
 * independent things wrong at once (e.g. a circuit breaker issue AND an
 * unrelated lag issue happening to coincide). Treating the whole snapshot as
 * one combined signature would mean an unrelated change elsewhere makes the
 * "signature" different, incorrectly resetting an ongoing incident's
 * tracking even though the original condition never actually cleared. So
 * each atomic anomalous fact (e.g. "circuitBreaker:customerClient:HALF_OPEN")
 * is opened, persisted, and resolved independently of whatever else is or
 * isn't anomalous in the same snapshot.
 * <p>
 * What counts as "anomalous" here is deliberately kept in exact sync with
 * HealthyStateRule.matches()'s definition of "healthy" (inverted) - both use
 * the same AnomalyThresholds constants. If these drift apart, this tracker
 * could count something as an ongoing incident that HealthyStateRule (and
 * therefore the rule engine's coverage/escalation behavior) considers fine,
 * or vice versa.
 */
@Component
public class IncidentTracker {

    private static final Logger log = LoggerFactory.getLogger(IncidentTracker.class);

    private final Map<String, OpenIncident> openIncidents = new ConcurrentHashMap<>();
    private final Map<String, Integer> resolvedOccurrenceCounts = new ConcurrentHashMap<>();

    /**
     * Called once per poll cycle with the freshly-built SystemState. Opens
     * any newly-anomalous facts, keeps already-open ones alive (updates
     * lastSeenAt), and resolves any previously-open fact that's no longer
     * present - incrementing that fact's recurrence count.
     */
    public void observe(SystemState state) {
        Set<String> currentFacts = computeAnomalousFacts(state);
        Instant now = Instant.now();

        // Resolve anything that was open but isn't anomalous anymore.
        for (String openFact : new ArrayList<>(openIncidents.keySet())) {
            if (!currentFacts.contains(openFact)) {
                resolve(openFact);
            }
        }

        // Open (or keep alive) everything currently anomalous.
        for (String fact : currentFacts) {
            OpenIncident incident = openIncidents.computeIfAbsent(fact, f -> {
                log.info("Incident opened: {}", f);
                return new OpenIncident(f, now);
            });
            incident.touch(now);
        }
    }

    private void resolve(String fact) {
        OpenIncident incident = openIncidents.remove(fact);
        if (incident == null) {
            return;
        }
        int occurrences = resolvedOccurrenceCounts.merge(fact, 1, Integer::sum);
        log.info("Incident resolved: {} (open for {}, occurrence #{})",
                fact, java.time.Duration.between(incident.firstSeenAt(), Instant.now()), occurrences);
        // Rule-promotion trigger (not yet implemented): once `occurrences` crosses
        // a configured threshold for this fact, this is the point to ask the LLM
        // to draft a RuleCandidate.
    }

    private Set<String> computeAnomalousFacts(SystemState state) {
        // TreeSet: stable iteration order, purely so log output and any future
        // debug/inspection endpoint reads consistently - not load-bearing for
        // correctness, since these are looked up by exact fact string either way.
        Set<String> facts = new TreeSet<>();

        state.circuitBreakers().entrySet().stream()
                .filter(e -> e.getValue() != SystemState.CircuitBreakerState.CLOSED)
                .forEach(e -> facts.add("circuitBreaker:" + e.getKey() + ":" + e.getValue()));

        state.outboxLagSeconds().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > AnomalyThresholds.OUTBOX_LAG_THRESHOLD_SECONDS)
                .forEach(e -> facts.add("outboxLag:" + e.getKey()));

        state.dlqDepth().entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > AnomalyThresholds.DLQ_DEPTH_THRESHOLD)
                .forEach(e -> facts.add("dlqDepth:" + e.getKey()));

        return facts;
    }

    public List<OpenIncident> openIncidents() {
        return openIncidents.values().stream()
                .sorted(Comparator.comparing(OpenIncident::fact))
                .toList();
    }

    public int occurrenceCount(String fact) {
        return resolvedOccurrenceCounts.getOrDefault(fact, 0);
    }

    public Map<String, Integer> occurrenceCounts() {
        return Map.copyOf(resolvedOccurrenceCounts);
    }
}
