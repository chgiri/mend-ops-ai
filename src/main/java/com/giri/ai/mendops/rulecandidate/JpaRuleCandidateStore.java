package com.giri.ai.mendops.rulecandidate;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The JPA-backed RuleCandidateStore - the drop-in replacement
 * RuleCandidateStore's interface was deliberately designed for (see that
 * interface's Javadoc). Replaces InMemoryRuleCandidateStore as the sole
 * implementation; nothing outside this class and RuleCandidateEntity knows
 * or cares that candidates are now persisted rather than held in a
 * ConcurrentHashMap.
 * <p>
 * Uses RuleCandidate.rehydrate() to reconstruct domain objects from stored
 * data - the same pattern PendingApproval.rehydrate() established for
 * ApprovalGate's reload.
 */
@Component
public class JpaRuleCandidateStore implements RuleCandidateStore {

    private final RuleCandidateAuditRepository repository;

    public JpaRuleCandidateStore(RuleCandidateAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(RuleCandidate candidate) {
        repository.save(toEntity(candidate));
    }

    @Override
    public Optional<RuleCandidate> findById(String id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    public List<RuleCandidate> findAll() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toDomain).toList();
    }

    @Override
    public List<RuleCandidate> findByStatus(RuleCandidate.Status status) {
        return repository.findAllByStatusOrderByCreatedAtDesc(status).stream().map(this::toDomain).toList();
    }

    private RuleCandidateEntity toEntity(RuleCandidate candidate) {
        List<RuleCandidateEntity.ConditionEmbeddable> conditionEntities = candidate.conditions().stream()
                .map(c -> new RuleCandidateEntity.ConditionEmbeddable(c.field(), c.operator(), c.value()))
                .toList();

        RuleCandidateEntity entity = new RuleCandidateEntity(
                candidate.id(), candidate.sourceFact(), candidate.occurrenceCountAtDrafting(),
                candidate.diagnosis(), conditionEntities, candidate.actionType(), candidate.actionParams(),
                candidate.status(), candidate.createdAt());
        entity.setResolvedAt(candidate.resolvedAt());
        return entity;
    }

    private RuleCandidate toDomain(RuleCandidateEntity entity) {
        List<RuleCandidate.Condition> conditions = entity.getConditions().stream()
                .map(c -> new RuleCandidate.Condition(c.getField(), c.getOperator(), c.getValue()))
                .toList();

        return RuleCandidate.rehydrate(
                entity.getId(), entity.getSourceFact(), entity.getOccurrenceCountAtDrafting(),
                entity.getDiagnosis(), conditions, entity.getActionType(), entity.getActionParams(),
                entity.getStatus(), entity.getCreatedAt(), entity.getResolvedAt());
    }
}
