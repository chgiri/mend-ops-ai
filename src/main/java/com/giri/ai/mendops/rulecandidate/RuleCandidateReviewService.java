package com.giri.ai.mendops.rulecandidate;

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

    public RuleCandidateReviewService(RuleCandidateStore store) {
        this.store = store;
    }

    public RuleCandidate get(String id) {
        return store.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No rule candidate with id " + id));
    }

    /** PENDING_REVIEW -> APPROVED_SHADOW. The candidate starts logging matches without acting. */
    public RuleCandidate approveToShadow(String id) {
        RuleCandidate candidate = get(id);
        requireStatus(candidate, RuleCandidate.Status.PENDING_REVIEW);
        candidate.markApprovedShadow();
        store.save(candidate);
        log.info("Rule candidate {} approved to shadow mode", id);
        return candidate;
    }

    /** APPROVED_SHADOW -> LIVE. Requires this separate, explicit call - never auto-promoted. */
    public RuleCandidate promoteToLive(String id) {
        RuleCandidate candidate = get(id);
        requireStatus(candidate, RuleCandidate.Status.APPROVED_SHADOW);
        candidate.markLive();
        store.save(candidate);
        log.info("Rule candidate {} promoted to LIVE", id);
        return candidate;
    }

    /** PENDING_REVIEW or APPROVED_SHADOW -> REJECTED. A shadow-mode candidate can still be rejected. */
    public RuleCandidate reject(String id) {
        RuleCandidate candidate = get(id);
        if (candidate.status() != RuleCandidate.Status.PENDING_REVIEW
                && candidate.status() != RuleCandidate.Status.APPROVED_SHADOW) {
            throw new IllegalStateException(
                    "Rule candidate " + id + " cannot be rejected from status " + candidate.status());
        }
        candidate.markRejected();
        store.save(candidate);
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
