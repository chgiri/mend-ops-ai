package com.giri.ai.mendops.rulecandidate;

import java.util.List;
import java.util.Optional;

/**
 * Pure storage for RuleCandidates - deliberately holds no drafting or review
 * logic itself (unlike ApprovalGate, which combines storage and business
 * logic in one class). Whatever ends up drafting candidates and whatever
 * ends up reviewing them should only ever talk to this interface, never an
 * implementation's internals directly - that's what makes swapping in a
 * JPA-backed implementation later (mirroring ApprovalAuditEntity/Repository)
 * a matter of adding one new @Component, not touching every caller.
 */
public interface RuleCandidateStore {

    void save(RuleCandidate candidate);

    Optional<RuleCandidate> findById(String id);

    List<RuleCandidate> findAll();

    List<RuleCandidate> findByStatus(RuleCandidate.Status status);
}
