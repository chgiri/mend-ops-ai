package com.giri.ai.mendops.rulecandidate;

import com.giri.ai.mendops.model.RemediationAction;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable persistence for a RuleCandidate - written by JpaRuleCandidateStore
 * and reloaded in full at startup (see RuleCandidateReviewService's
 * @PostConstruct hook), so an APPROVED_SHADOW or LIVE candidate's
 * DataDrivenRule survives a restart instead of the rule-promotion flow
 * silently resetting every time the process restarts.
 * <p>
 * Only works because RuleCandidate stores everything as DATA (conditions,
 * action type, params) rather than anything non-serializable - see
 * RuleCandidate's Javadoc. conditions uses a plain JPA @ElementCollection of
 * an @Embeddable (rule_candidate_condition, ordered) and actionParams uses
 * the same Map-side-table pattern ApprovalAuditEntity already established
 * (rule_candidate_action_param) - no JSON column library needed beyond what
 * spring-boot-starter-data-jpa already brings.
 */
@Entity
@Table(name = "rule_candidate")
public class RuleCandidateEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String sourceFact;

    @Column(nullable = false)
    private int occurrenceCountAtDrafting;

    @Lob
    private String diagnosis;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rule_candidate_condition", joinColumns = @JoinColumn(name = "rule_candidate_id"))
    @OrderColumn(name = "condition_order")
    private List<ConditionEmbeddable> conditions = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RemediationAction.ActionType actionType;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "rule_candidate_action_param", joinColumns = @JoinColumn(name = "rule_candidate_id"))
    @MapKeyColumn(name = "param_key")
    @Column(name = "param_value")
    private Map<String, String> actionParams = new HashMap<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RuleCandidate.Status status;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant resolvedAt;

    /** JPA requires a no-arg constructor - not for application use. */
    protected RuleCandidateEntity() {
    }

    public RuleCandidateEntity(String id, String sourceFact, int occurrenceCountAtDrafting, String diagnosis,
                                List<ConditionEmbeddable> conditions, RemediationAction.ActionType actionType,
                                Map<String, String> actionParams, RuleCandidate.Status status, Instant createdAt) {
        this.id = id;
        this.sourceFact = sourceFact;
        this.occurrenceCountAtDrafting = occurrenceCountAtDrafting;
        this.diagnosis = diagnosis;
        this.conditions = conditions == null ? new ArrayList<>() : new ArrayList<>(conditions);
        this.actionType = actionType;
        this.actionParams = actionParams == null ? new HashMap<>() : new HashMap<>(actionParams);
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getSourceFact() {
        return sourceFact;
    }

    public int getOccurrenceCountAtDrafting() {
        return occurrenceCountAtDrafting;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public List<ConditionEmbeddable> getConditions() {
        return conditions;
    }

    public RemediationAction.ActionType getActionType() {
        return actionType;
    }

    public Map<String, String> getActionParams() {
        return actionParams;
    }

    public RuleCandidate.Status getStatus() {
        return status;
    }

    public void setStatus(RuleCandidate.Status status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    /** One condition, embedded - mirrors RuleCandidate.Condition's three fields exactly. */
    @Embeddable
    public static class ConditionEmbeddable {

        private String field;

        @Enumerated(EnumType.STRING)
        private RuleCandidate.Operator operator;

        private String value;

        protected ConditionEmbeddable() {
        }

        public ConditionEmbeddable(String field, RuleCandidate.Operator operator, String value) {
            this.field = field;
            this.operator = operator;
            this.value = value;
        }

        public String getField() {
            return field;
        }

        public RuleCandidate.Operator getOperator() {
            return operator;
        }

        public String getValue() {
            return value;
        }
    }
}
