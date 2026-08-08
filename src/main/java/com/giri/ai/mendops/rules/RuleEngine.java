package com.giri.ai.mendops.rules;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic first pass over a SystemState. Evaluates registered rules in
 * order; first match wins. If nothing matches, the caller (AgentOrchestrator)
 * is responsible for escalating to the LLM.
 * <p>
 * Tracks matched vs. unmatched counts so we can report rule-engine coverage
 * over time - the "92% handled without an LLM call" metric that's the whole
 * point of the hybrid architecture.
 */
@Component
public class RuleEngine {

    private final List<RemediationRule> rules;

    private final AtomicLong matchedCount = new AtomicLong();
    private final AtomicLong unmatchedCount = new AtomicLong();

    public RuleEngine(List<RemediationRule> rules) {
        this.rules = rules;
    }

    public Optional<RemediationAction> evaluate(SystemState state) {
        for (RemediationRule rule : rules) {
            if (rule.matches(state)) {
                matchedCount.incrementAndGet();
                return Optional.of(rule.actionFor(state));
            }
        }
        unmatchedCount.incrementAndGet();
        return Optional.empty();
    }

    public double coverageRatio() {
        long matched = matchedCount.get();
        long total = matched + unmatchedCount.get();
        return total == 0 ? 0.0 : (double) matched / total;
    }

    public long matchedCount() {
        return matchedCount.get();
    }

    public long unmatchedCount() {
        return unmatchedCount.get();
    }
}
