package com.giri.ai.mendops.rulecandidate;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory RuleCandidateStore - lost on restart, same trade-off ApprovalGate
 * accepted before it got JPA persistence. Deliberately the ONLY thing in
 * this feature that knows candidates aren't persisted yet; everything else
 * (whatever ends up drafting/reviewing them) only depends on the
 * RuleCandidateStore interface, so replacing this with a JPA-backed
 * implementation later needs no changes anywhere else.
 */
@Component
public class InMemoryRuleCandidateStore implements RuleCandidateStore {

    private final Map<String, RuleCandidate> candidates = new ConcurrentHashMap<>();

    @Override
    public void save(RuleCandidate candidate) {
        candidates.put(candidate.id(), candidate);
    }

    @Override
    public Optional<RuleCandidate> findById(String id) {
        return Optional.ofNullable(candidates.get(id));
    }

    @Override
    public List<RuleCandidate> findAll() {
        return List.copyOf(candidates.values());
    }

    @Override
    public List<RuleCandidate> findByStatus(RuleCandidate.Status status) {
        return candidates.values().stream()
                .filter(c -> c.status() == status)
                .toList();
    }
}
