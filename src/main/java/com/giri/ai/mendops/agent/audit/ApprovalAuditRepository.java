package com.giri.ai.mendops.agent.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalAuditRepository extends JpaRepository<ApprovalAuditEntity, String> {

    List<ApprovalAuditEntity> findAllByOrderByCreatedAtDesc();
}
