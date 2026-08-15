package com.giri.ai.mendops.rules;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShadowMatchRepository extends JpaRepository<ShadowMatchEntity, String> {

    List<ShadowMatchEntity> findByRuleIdOrderByMatchedAtDesc(String ruleId, Pageable pageable);
}
