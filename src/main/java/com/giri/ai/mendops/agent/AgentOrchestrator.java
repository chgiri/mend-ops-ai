package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.remediation.PagingNotifier;
import com.giri.ai.mendops.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

    public String handle(SystemState state) {
        Optional<RemediationAction> ruleMatch = ruleEngine.evaluate(state);

        if (ruleMatch.isPresent()) {
            RemediationAction action = ruleMatch.get();
            log.info("Resolved by rule engine: {}", action);
            String dispatchResult = dispatch(action);
            return "[rule-engine] " + action.diagnosis() + " -> " + action.actionType()
                    + " (" + action.targetService() + "). " + dispatchResult;
        }

        log.info("No rule matched - escalating to LLM. Coverage so far: {}%",
                Math.round(ruleEngine.coverageRatio() * 100));
        return "[llm-escalation] " + escalationService.diagnoseAndAct(state);
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
}
