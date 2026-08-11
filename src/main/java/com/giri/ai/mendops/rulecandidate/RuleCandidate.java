package com.giri.ai.mendops.rulecandidate;

import com.giri.ai.mendops.model.RemediationAction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A rule candidate drafted (by the LLM, once built) from a recurring
 * incident tracked by IncidentTracker. Held entirely as DATA - a list of
 * conditions plus an action - not as generated code: an LLM-authored Java
 * class that gets compiled/loaded at runtime would be arbitrary code
 * execution from model output, a hard no regardless of sandboxing. A single
 * hand-written interpreter (DataDrivenRule, not yet built) evaluates a
 * candidate's conditions against a SystemState the same way any other
 * RemediationRule does.
 * <p>
 * actionType/actionParams deliberately reuse the exact same shapes
 * RemediationActionExecutor already consumes (see PendingApproval) - once a
 * candidate reaches LIVE and actually fires, executing it is
 * RemediationActionExecutor.execute(actionType, actionParams) unchanged, no
 * new dispatch logic needed for that part.
 * <p>
 * Mutable by design (status/resolvedAt change in place), matching
 * PendingApproval's shape - not exposed directly over REST once a
 * controller exists; that'll need its own view DTO the same way
 * ApprovalController does.
 */
public class RuleCandidate {

    public enum Status {
        PENDING_REVIEW, APPROVED_SHADOW, LIVE, REJECTED
    }

    public enum Operator {
        EQUALS, NOT_EQUALS, GREATER_THAN, LESS_THAN
    }

    /**
     * One condition against a SystemState field. field is a dotted path
     * matching SystemState's own map structure - e.g.
     * "circuitBreakers.customerClient" or "outboxLagSeconds.shipment-service"
     * - interpreted by DataDrivenRule (not yet built). value is stored as a
     * String regardless of the field's real type (an enum name like
     * "HALF_OPEN", or a number like "60" for a GREATER_THAN lag check) -
     * DataDrivenRule interprets it contextually per field/operator pair.
     * <p>
     * All of a candidate's conditions are implicitly AND'd together - no OR
     * grouping in v1, matching the flat-AND shape the three built-in rules
     * already use.
     */
    public record Condition(String field, Operator operator, String value) {
    }

    private final String id;
    private final String sourceFact;
    private final int occurrenceCountAtDrafting;
    private final String diagnosis;
    private final List<Condition> conditions;
    private final RemediationAction.ActionType actionType;
    private final Map<String, String> actionParams;

    private volatile Status status = Status.PENDING_REVIEW;
    private volatile Instant createdAt = Instant.now();
    private volatile Instant resolvedAt;

    public RuleCandidate(String id, String sourceFact, int occurrenceCountAtDrafting, String diagnosis,
                          List<Condition> conditions, RemediationAction.ActionType actionType,
                          Map<String, String> actionParams) {
        this.id = id;
        this.sourceFact = sourceFact;
        this.occurrenceCountAtDrafting = occurrenceCountAtDrafting;
        this.diagnosis = diagnosis;
        this.conditions = List.copyOf(conditions);
        this.actionType = actionType;
        this.actionParams = Map.copyOf(actionParams);
    }

    /** Rebuilds a candidate from persisted state - see PendingApproval.rehydrate for the same pattern. */
    public static RuleCandidate rehydrate(String id, String sourceFact, int occurrenceCountAtDrafting,
                                           String diagnosis, List<Condition> conditions,
                                           RemediationAction.ActionType actionType,
                                           Map<String, String> actionParams, Status status,
                                           Instant createdAt, Instant resolvedAt) {
        RuleCandidate candidate = new RuleCandidate(
                id, sourceFact, occurrenceCountAtDrafting, diagnosis, conditions, actionType, actionParams);
        candidate.status = status;
        candidate.createdAt = createdAt;
        candidate.resolvedAt = resolvedAt;
        return candidate;
    }

    public void markApprovedShadow() {
        this.status = Status.APPROVED_SHADOW;
        this.resolvedAt = Instant.now();
    }

    public void markLive() {
        this.status = Status.LIVE;
    }

    public void markRejected() {
        this.status = Status.REJECTED;
        this.resolvedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String sourceFact() {
        return sourceFact;
    }

    public int occurrenceCountAtDrafting() {
        return occurrenceCountAtDrafting;
    }

    public String diagnosis() {
        return diagnosis;
    }

    public List<Condition> conditions() {
        return conditions;
    }

    public RemediationAction.ActionType actionType() {
        return actionType;
    }

    public Map<String, String> actionParams() {
        return actionParams;
    }

    public Status status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant resolvedAt() {
        return resolvedAt;
    }
}
