package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RuleEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The core cascade: try the deterministic rule engine first (fast, free,
 * auditable); only fall back to the LLM when nothing matches. This is the
 * seam where the "rule-engine coverage over time" metric and the future
 * rule-promotion flow (LLM proposes a new rule from a resolved unknown,
 * human approves, it gets added to RuleEngine's rule list) will live.
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final RuleEngine ruleEngine;
    private final EscalationService escalationService;

    public AgentOrchestrator(RuleEngine ruleEngine, EscalationService escalationService) {
        this.ruleEngine = ruleEngine;
        this.escalationService = escalationService;
    }

    public String handle(SystemState state) {
        Optional<RemediationAction> ruleMatch = ruleEngine.evaluate(state);

        if (ruleMatch.isPresent()) {
            RemediationAction action = ruleMatch.get();
            log.info("Resolved by rule engine: {}", action);
            return "[rule-engine] " + action.diagnosis() + " -> " + action.actionType()
                    + " (" + action.targetService() + ")";
        }

        log.info("No rule matched - escalating to LLM. Coverage so far: {}%",
                Math.round(ruleEngine.coverageRatio() * 100));
        return "[llm-escalation] " + escalationService.diagnoseAndAct(state);
    }
}
