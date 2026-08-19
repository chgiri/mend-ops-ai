package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.remediation.PagingNotifier;
import com.giri.ai.mendops.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * The core cascade: try the deterministic rule engine first (fast, free,
 * auditable); only fall back to the LLM when nothing matches.
 * <p>
 * A rule match is dispatched through the exact same real execution path the
 * LLM path already uses - this used to only log the diagnosis and return a
 * string, meaning even a rule promoted all the way to LIVE (see
 * rulecandidate package) never actually did anything real. PAGE_ONCALL
 * executes immediately via PagingNotifier (matching RemediationTools.pageOncall
 * - paging isn't gated). ADJUST_RETRY_BUDGET/REPLAY_DLQ_BATCH go through
 * ApprovalGate.propose() - deliberately still gated behind human approval
 * even for a rule match, including a promoted LIVE DataDrivenRule: a
 * candidate's human review happens once, at promotion time, over its
 * condition/action-type/default-parameter shape - it is NOT a standing
 * authorization to auto-execute every future match without anyone looking
 * at it again. The original guardrail ("every ActionType except PAGE_ONCALL
 * needs approval") was about infrastructure risk, which doesn't change
 * based on whether a rule or an LLM decided the action - so "gets cheaper
 * over time" from rule-promotion means fewer LLM calls, not less human
 * oversight; the queued-approval step stays either way.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final RuleEngine ruleEngine;
    private final EscalationService escalationService;
    private final PagingNotifier pagingNotifier;
    private final ApprovalGate approvalGate;

    public AgentOrchestrator(RuleEngine ruleEngine, EscalationService escalationService,
                              PagingNotifier pagingNotifier, ApprovalGate approvalGate) {
        this.ruleEngine = ruleEngine;
        this.escalationService = escalationService;
        this.pagingNotifier = pagingNotifier;
        this.approvalGate = approvalGate;
    }

    public AgentDecision handle(SystemState state) {
        Optional<RemediationAction> ruleMatch = ruleEngine.evaluate(state);

        if (ruleMatch.isPresent()) {
            RemediationAction action = ruleMatch.get();
            log.info("Resolved by rule engine: {}", action);
            String dispatchResult = dispatch(action);
            String summary = action.diagnosis() + " -> " + action.actionType()
                    + " (" + action.targetService() + "). " + dispatchResult;
            return new AgentDecision("RULE_ENGINE", List.of(action.actionType()), summary);
        }

        log.info("No rule matched - escalating to LLM. Coverage so far: {}%",
                Math.round(ruleEngine.coverageRatio() * 100));
        EscalationService.EscalationOutcome outcome = escalationService.diagnoseAndAct(state);
        return new AgentDecision("LLM_ESCALATION", outcome.invokedActions(), outcome.diagnosis());
    }

    private String dispatch(RemediationAction action) {
        return switch (action.actionType()) {
            case PAGE_ONCALL -> {
                String summary = action.actionParams().getOrDefault("summary", action.diagnosis());
                pagingNotifier.page(summary);
                yield "Paged on-call.";
            }
            case ADJUST_RETRY_BUDGET, REPLAY_DLQ_BATCH -> {
                String id = approvalGate.propose(action.actionType(), action.diagnosis(), action.actionParams());
                yield "Proposed and awaiting human approval (approvalId=" + id + "). No action taken yet.";
            }
            case NO_ACTION -> "Nothing to do.";
        };
    }

    /**
     * @param source          "RULE_ENGINE" or "LLM_ESCALATION" - a plain String rather
     *                        than reusing RemediationAction.Source so this doesn't
     *                        imply a 1:1 mapping that isn't quite true (a rule match
     *                        always yields exactly one action; LLM escalation can
     *                        yield zero, one, or multiple - see actionsInvoked).
     * @param actionsInvoked  what actually happened, in order. For a rule match this
     *                        is always a singleton list (deterministic). For LLM
     *                        escalation this is ground truth from RemediationTools'
     *                        invocation tracking (see EscalationService), NOT parsed
     *                        from the summary text - empty if the model responded
     *                        without calling any tool at all.
     * @param summary         human-readable text for display/logging - do not try to
     *                        parse this to determine which action fired; use
     *                        actionsInvoked instead. This is exactly the field this
     *                        record exists to make unnecessary to parse.
     */
    public record AgentDecision(String source, List<RemediationAction.ActionType> actionsInvoked, String summary) {
    }
}
