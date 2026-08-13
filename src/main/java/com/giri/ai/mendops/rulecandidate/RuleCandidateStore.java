package com.giri.ai.mendops.rulecandidate;

import java.util.List;
import java.util.Optional;

/**
 * Pure storage for RuleCandidates - deliberately holds no drafting or review
 * logic itself (unlike ApprovalGate, which combines storage and business
 * logic in one class). Whatever drafts candidates (RuleCandidateDraftingService)
 * or reviews them (RuleCandidateReviewService, RuleCandidateController) only
 * ever talks to this interface, never an implementation's internals directly.
 * <p>
 * Now backed by JpaRuleCandidateStore (mirroring ApprovalAuditEntity/Repository) -
 * this interface was deliberately designed for that swap in advance (it
 * originally shipped with only an in-memory implementation), and the swap
 * needed zero changes to any caller, exactly as intended.
 */
public interface RuleCandidateStore {

    void save(RuleCandidate candidate);

    Optional<RuleCandidate> findById(String id);

    List<RuleCandidate> findAll();

    List<RuleCandidate> findByStatus(RuleCandidate.Status status);
}
