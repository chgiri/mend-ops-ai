package com.giri.ai.mendops.rules;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic first pass over a SystemState. Evaluates rules in order:
 * the static, Spring-injected rules first, then dynamic "live" rules
 * (promoted RuleCandidates - see rulecandidate package) after them - first
 * match wins either way. If nothing matches, the caller (AgentOrchestrator)
 * is responsible for escalating to the LLM.
 * <p>
 * Separately, shadow rules (RuleCandidates approved but not yet promoted to
 * LIVE) are evaluated on EVERY call, regardless of whether a static/live
 * rule already matched - the point of shadow mode is observing whether a
 * candidate's conditions correctly fire (and don't false-positive) against
 * real traffic before it's trusted to actually short-circuit anything, so
 * it needs to see every poll independently, not just the ones nothing else
 * already handled. A shadow match is both logged live AND recorded to
 * ShadowMatchHistory (see evaluateShadowRules) so it can be reviewed after
 * the fact, not just watched live in logs.
 * <p>
 * Dynamic rule lists start empty at construction, but are repopulated
 * immediately after by RuleCandidateReviewService's @PostConstruct reload
 * hook, which re-registers every persisted APPROVED_SHADOW/LIVE
 * RuleCandidate (see RuleCandidateStore/JpaRuleCandidateStore) - so a
 * promoted rule survives a restart rather than silently going inert.
 * <p>
 * Tracks matched vs. unmatched counts so we can report rule-engine coverage
 * over time - the "92% handled without an LLM call" metric that's the whole
 * point of the hybrid architecture. Live dynamic rule matches count toward
 * this the same as static rule matches - a promoted candidate reducing LLM
 * calls is the entire point of the rule-promotion flow. A rule match (static
 * or live dynamic) also dispatches a real action via AgentOrchestrator - see
 * that class for the PAGE_ONCALL/ADJUST_RETRY_BUDGET/REPLAY_DLQ_BATCH
 * dispatch, which RuleEngine itself has no involvement in.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<RemediationRule> staticRules;
    private final List<RemediationRule> liveDynamicRules = new CopyOnWriteArrayList<>();
    private final List<RemediationRule> shadowRules = new CopyOnWriteArrayList<>();
    private final ShadowMatchHistory shadowMatchHistory;

    private final AtomicLong matchedCount = new AtomicLong();
    private final AtomicLong unmatchedCount = new AtomicLong();

    public RuleEngine(List<RemediationRule> staticRules, ShadowMatchHistory shadowMatchHistory) {
        this.staticRules = staticRules;
        this.shadowMatchHistory = shadowMatchHistory;
    }

    public Optional<RemediationAction> evaluate(SystemState state) {
        evaluateShadowRules(state);

        for (RemediationRule rule : staticRules) {
            if (rule.matches(state)) {
                matchedCount.incrementAndGet();
                return Optional.of(rule.actionFor(state));
            }
        }
        for (RemediationRule rule : liveDynamicRules) {
            if (rule.matches(state)) {
                matchedCount.incrementAndGet();
                return Optional.of(rule.actionFor(state));
            }
        }

        unmatchedCount.incrementAndGet();
        return Optional.empty();
    }

    private void evaluateShadowRules(SystemState state) {
        for (RemediationRule rule : shadowRules) {
            if (rule.matches(state)) {
                RemediationAction action = rule.actionFor(state);
                log.info("[SHADOW] Rule {} would have matched: {}", rule.id(), action);
                shadowMatchHistory.record(new ShadowMatchRecord(
                        UUID.randomUUID().toString(), rule.id(), Instant.now(), action.diagnosis(),
                        action.actionType() + " (" + action.targetService() + ")"));
            }
        }
    }

    /** Adds (or replaces, by id) a rule to the live dynamic list - matches participate in real evaluation. */
    public void addLiveRule(RemediationRule rule) {
        liveDynamicRules.removeIf(r -> r.id().equals(rule.id()));
        liveDynamicRules.add(rule);
    }

    public void removeLiveRule(String ruleId) {
        liveDynamicRules.removeIf(r -> r.id().equals(ruleId));
    }

    /** Adds (or replaces, by id) a rule to the shadow list - matches are logged and recorded, never returned from evaluate(). */
    public void addShadowRule(RemediationRule rule) {
        shadowRules.removeIf(r -> r.id().equals(rule.id()));
        shadowRules.add(rule);
    }

    public void removeShadowRule(String ruleId) {
        shadowRules.removeIf(r -> r.id().equals(ruleId));
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
