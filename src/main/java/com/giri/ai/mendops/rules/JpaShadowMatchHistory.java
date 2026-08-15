package com.giri.ai.mendops.rules;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The JPA-backed ShadowMatchHistory - replaces the original in-memory
 * implementation as the sole ShadowMatchHistory bean. Every match is saved;
 * forRule() queries only the most recent MAX_DISPLAY_RECORDS via a Pageable
 * top-N query, so old shadow-match history is never destroyed the way an
 * in-memory eviction policy would - it's simply not all returned by this one
 * query.
 */
@Component
public class JpaShadowMatchHistory implements ShadowMatchHistory {

    private static final int MAX_DISPLAY_RECORDS = 50;

    private final ShadowMatchRepository repository;

    public JpaShadowMatchHistory(ShadowMatchRepository repository) {
        this.repository = repository;
    }

    @Override
    public void record(ShadowMatchRecord record) {
        repository.save(new ShadowMatchEntity(
                record.id(), record.ruleId(), record.matchedAt(), record.diagnosis(), record.actionSummary()));
    }

    @Override
    public List<ShadowMatchRecord> forRule(String ruleId) {
        return repository.findByRuleIdOrderByMatchedAtDesc(ruleId, PageRequest.of(0, MAX_DISPLAY_RECORDS)).stream()
                .map(e -> new ShadowMatchRecord(e.getId(), e.getRuleId(), e.getMatchedAt(), e.getDiagnosis(), e.getActionSummary()))
                .toList();
    }
}
