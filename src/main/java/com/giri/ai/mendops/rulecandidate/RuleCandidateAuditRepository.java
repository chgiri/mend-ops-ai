package com.giri.ai.mendops.rulecandidate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleCandidateAuditRepository extends JpaRepository<RuleCandidateEntity, String> {

    List<RuleCandidateEntity> findAllByOrderByCreatedAtDesc();

    List<RuleCandidateEntity> findAllByStatusOrderByCreatedAtDesc(RuleCandidate.Status status);
}
