package com.giri.ai.mendops.rulecandidate;

import com.giri.ai.mendops.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Validates and performs RuleCandidate's status transitions - mirrors
 * ApprovalGate's role for PendingApproval (validate current status before
 * mutating, throw IllegalStateException on an invalid transition), kept
 * separate from RuleCandidateStore (pure storage) and RuleCandidateController
 * (HTTP concerns only).
 * <p>
 * Also owns registering/deregistering the corresponding DataDrivenRule with
 * RuleEngine as a candidate moves between states - RuleEngine only ever
 * holds a rule for candidates currently APPROVED_SHADOW (in its shadow list)
 * or LIVE (in its live list); PENDING_REVIEW and REJECTED candidates aren't
 * registered anywhere in RuleEngine at all.
 * <p>
 * Explicitly re-saves via the store after every mutation rather than relying
 * on in-memory reference mutation being visible for free - InMemoryRuleCandidateStore
 * doesn't strictly need that today, but a future JPA-backed store would, and
 * this is the seam that's meant to swap in cleanly (see RuleCandidateStore's
 * Javadoc).
 */
@Service
public class RuleCandidateReviewService {

    private static final Logger log = LoggerFactory.getLogger(RuleCandidateReviewService.class);

    private final RuleCandidateStore store;
    private final RuleEngine ruleEngine;

    public RuleCandidateReviewService(RuleCandidateStore store, RuleEngine ruleEngine) {
        this.store = store;
        this.ruleEngine = ruleEngine;
    }

    public RuleCandidate get(String id) {
        return store.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No rule candidate with id " + id));
    }

    /**
     * PENDING_REVIEW -> APPROVED_SHADOW. Registers a DataDrivenRule with
     * RuleEngine's shadow list - it starts logging "would have matched"
     * against every real poll, without ever short-circuiting evaluate().
     */
    public RuleCandidate approveToShadow(String id) {
        RuleCandidate candidate = get(id);
        requireStatus(candidate, RuleCandidate.Status.PENDING_REVIEW);
        candidate.markApprovedShadow();
        store.save(candidate);
        ruleEngine.addShadowRule(new DataDrivenRule(candidate));
        log.info("Rule candidate {} approved to shadow mode", id);
        return candidate;
    }

    /**
     * APPROVED_SHADOW -> LIVE. Requires this separate, explicit call - never
     * auto-promoted. Moves the rule from RuleEngine's shadow list to its
     * live list - it now participates in real evaluation and can
     * short-circuit LLM escalation for matching polls.
     */
    public RuleCandidate promoteToLive(String id) {
        RuleCandidate candidate = get(id);
        requireStatus(candidate, RuleCandidate.Status.APPROVED_SHADOW);
        candidate.markLive();
        store.save(candidate);
        ruleEngine.removeShadowRule(id);
        ruleEngine.addLiveRule(new DataDrivenRule(candidate));
        log.info("Rule candidate {} promoted to LIVE", id);
        return candidate;
    }

    /**
     * PENDING_REVIEW or APPROVED_SHADOW -> REJECTED. A shadow-mode candidate
     * can still be rejected - its DataDrivenRule is deregistered from
     * RuleEngine's shadow list if it was there.
     */
    public RuleCandidate reject(String id) {
        RuleCandidate candidate = get(id);
        RuleCandidate.Status previousStatus = candidate.status();
        if (previousStatus != RuleCandidate.Status.PENDING_REVIEW
                && previousStatus != RuleCandidate.Status.APPROVED_SHADOW) {
            throw new IllegalStateException(
                    "Rule candidate " + id + " cannot be rejected from status " + previousStatus);
        }
        candidate.markRejected();
        store.save(candidate);
        if (previousStatus == RuleCandidate.Status.APPROVED_SHADOW) {
            ruleEngine.removeShadowRule(id);
        }
        log.info("Rule candidate {} rejected", id);
        return candidate;
    }

    private void requireStatus(RuleCandidate candidate, RuleCandidate.Status required) {
        if (candidate.status() != required) {
            throw new IllegalStateException(
                    "Rule candidate " + candidate.id() + " must be " + required + " but is " + candidate.status());
        }
    }
}
