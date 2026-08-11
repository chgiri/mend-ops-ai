package com.giri.ai.mendops.rulecandidate;

import com.giri.ai.mendops.incident.IncidentResolvedEvent;
import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.rules.AnomalousFact;
import com.giri.ai.mendops.rules.AnomalyThresholds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Listens for IncidentResolvedEvent and, once a fact has recurred at least
 * {@code mendops.rule-candidate.recurrence-threshold} times, asks the LLM to
 * draft a RuleCandidate for it.
 * <p>
 * The candidate's conditions and the action's target identifier (which
 * circuit breaker / outbox service / DLQ topic) are derived deterministically
 * from the fact itself via AnomalousFact - NOT left to the LLM. A fact only
 * exists because some field already crossed a known AnomalyThresholds value,
 * so the natural condition is just that same crossing, expressed as data;
 * inventing a fresh threshold from scratch would be strictly less reliable
 * than reusing the one that's already known to be meaningful. The LLM's job
 * narrows to what genuinely needs judgment: is this worth auto-remediating
 * at all, which action fits, and what's a reasonable value for that action's
 * one tunable parameter - see RuleCandidateDraft's Javadoc.
 * <p>
 * Deliberately does not bind any tools to its ChatClient (unlike
 * EscalationService) - this call should only ever produce a structured
 * proposal, never take any real action itself.
 */
@Service
public class RuleCandidateDraftingService {

    private static final Logger log = LoggerFactory.getLogger(RuleCandidateDraftingService.class);

    private static final int DEFAULT_MAX_ATTEMPTS = 5;
    private static final int DEFAULT_REPLAY_COUNT = 10;

    private static final Set<RuleCandidate.Status> BLOCKS_REDRAFTING = Set.of(
            RuleCandidate.Status.PENDING_REVIEW, RuleCandidate.Status.APPROVED_SHADOW, RuleCandidate.Status.LIVE);

    private static final String SYSTEM_PROMPT = """
            You are drafting a PROPOSED remediation rule for an order management system
            built from microservices (oms-main, product-service, customer-service,
            shipment-service) communicating over Kafka with a transactional outbox
            pattern, and Resilience4j circuit breakers guarding synchronous calls
            between services.

            A specific anomalous condition has now recurred multiple times, each time
            requiring an LLM to diagnose and decide on a remediation from scratch. Your
            job is to propose turning this into a standing rule so future occurrences
            can be handled automatically without an LLM call, pending human review.

            The condition itself (which field, what threshold) is already fixed and
            given to you - you are NOT deciding that part. Your job is only:
            1. A short diagnosis explaining what this condition means and why it
               warrants a standing rule.
            2. Which action fits: ADJUST_RETRY_BUDGET (widen retry budget - use when the
               dependency itself seems fine but calls are failing transiently),
               REPLAY_DLQ_BATCH (replay dead-lettered messages - use when a backlog
               built up during an outage that has since cleared), or PAGE_ONCALL (defer
               to a human every time - use when the right response is genuinely
               situational and shouldn't be automated).
            3. One reasonable parameter value for whichever action you choose
               (maxAttempts for ADJUST_RETRY_BUDGET, replayCount for REPLAY_DLQ_BATCH,
               or a pageSummary template for PAGE_ONCALL). A human will review this
               proposal - including this parameter - before it's ever active, so
               propose a reasonable starting point rather than being overly cautious.

            Do NOT include which specific service/topic this targets in your reasoning
            output - that's already known and will be filled in separately.
            """;

    private final ChatClient chatClient;
    private final RuleCandidateProperties properties;
    private final RuleCandidateStore store;

    public RuleCandidateDraftingService(ChatClient.Builder chatClientBuilder, RuleCandidateProperties properties,
                                         RuleCandidateStore store) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
        this.properties = properties;
        this.store = store;
    }

    @EventListener
    public void onIncidentResolved(IncidentResolvedEvent event) {
        if (event.occurrenceCount() < properties.recurrenceThreshold()) {
            return;
        }

        boolean alreadyCovered = store.findAll().stream()
                .anyMatch(c -> c.sourceFact().equals(event.fact()) && BLOCKS_REDRAFTING.contains(c.status()));
        if (alreadyCovered) {
            log.debug("Skipping candidate drafting for '{}' - already has a non-terminal candidate", event.fact());
            return;
        }

        AnomalousFact fact = AnomalousFact.parse(event.fact()).orElse(null);
        if (fact == null) {
            log.warn("Could not parse fact '{}' - skipping candidate drafting", event.fact());
            return;
        }

        log.info("Fact '{}' has recurred {} times (threshold {}) - drafting a rule candidate",
                event.fact(), event.occurrenceCount(), properties.recurrenceThreshold());

        List<RuleCandidate.Condition> conditions = conditionsFor(fact);
        RuleCandidateDraft draft = requestDraft(event, conditions);

        RemediationAction.ActionType actionType = draft.actionType() != null
                ? draft.actionType() : RemediationAction.ActionType.PAGE_ONCALL;
        Map<String, String> actionParams = actionParamsFor(actionType, fact, draft);

        RuleCandidate candidate = new RuleCandidate(
                UUID.randomUUID().toString(), event.fact(), event.occurrenceCount(),
                draft.diagnosis(), conditions, actionType, actionParams);

        store.save(candidate);
        log.info("Drafted rule candidate {} for '{}': {} -> {}",
                candidate.id(), event.fact(), actionType, actionParams);
    }

    private List<RuleCandidate.Condition> conditionsFor(AnomalousFact fact) {
        return switch (fact.kind()) {
            case CIRCUIT_BREAKER -> List.of(new RuleCandidate.Condition(
                    "circuitBreakers." + fact.target(), RuleCandidate.Operator.EQUALS, fact.value()));
            case OUTBOX_LAG -> List.of(new RuleCandidate.Condition(
                    "outboxLagSeconds." + fact.target(), RuleCandidate.Operator.GREATER_THAN,
                    String.valueOf(AnomalyThresholds.OUTBOX_LAG_THRESHOLD_SECONDS)));
            case DLQ_DEPTH -> List.of(new RuleCandidate.Condition(
                    "dlqDepth." + fact.target(), RuleCandidate.Operator.GREATER_THAN,
                    String.valueOf(AnomalyThresholds.DLQ_DEPTH_THRESHOLD)));
        };
    }

    private RuleCandidateDraft requestDraft(IncidentResolvedEvent event, List<RuleCandidate.Condition> conditions) {
        String userMessage = """
                Recurring condition: %s
                Occurrences so far: %d
                Derived rule condition(s): %s

                Draft a proposal for this.
                """.formatted(event.fact(), event.occurrenceCount(), conditions);

        return chatClient.prompt()
                .user(userMessage)
                .call()
                .entity(RuleCandidateDraft.class);
    }

    private Map<String, String> actionParamsFor(RemediationAction.ActionType actionType, AnomalousFact fact,
                                                 RuleCandidateDraft draft) {
        return switch (actionType) {
            case ADJUST_RETRY_BUDGET -> Map.of(
                    "serviceName", fact.target(),
                    "maxAttempts", String.valueOf(draft.maxAttempts() != null ? draft.maxAttempts() : DEFAULT_MAX_ATTEMPTS));
            case REPLAY_DLQ_BATCH -> Map.of(
                    "topic", fact.target(),
                    "count", String.valueOf(draft.replayCount() != null ? draft.replayCount() : DEFAULT_REPLAY_COUNT));
            case PAGE_ONCALL -> Map.of(
                    "summary", draft.pageSummary() != null
                            ? draft.pageSummary() : "Recurring incident: " + fact.toFactString());
            case NO_ACTION -> Map.of();
        };
    }
}
