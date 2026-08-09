package com.giri.ai.mendops.controller;

import com.giri.ai.mendops.agent.AgentOrchestrator;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RuleEngine;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Demo/dev entry point for driving the agent manually. In a later stage this
 * gets replaced (or supplemented) by a scheduled poller that pulls real
 * SystemState snapshots from OMS's actuator/Prometheus/DB, rather than
 * accepting one over HTTP.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class AgentController {

    private final AgentOrchestrator orchestrator;
    private final RuleEngine ruleEngine;

    public AgentController(AgentOrchestrator orchestrator, RuleEngine ruleEngine) {
        this.orchestrator = orchestrator;
        this.ruleEngine = ruleEngine;
    }

    @PostMapping("/evaluate")
    public String evaluate(@RequestBody SystemState state) {
        return orchestrator.handle(state);
    }

    /** Convenience endpoint: known pattern, should resolve via the rule engine, no LLM call. */
    @PostMapping("/demo/product-circuit-open")
    public String demoProductCircuitOpen() {
        SystemState state = new SystemState(
                Instant.now(),
                Map.of("productClient", SystemState.CircuitBreakerState.OPEN),
                Map.of(),
                Map.of()
        );
        return orchestrator.handle(state);
    }

    /** Convenience endpoint: unusual combination, should have no rule match, forces LLM escalation. */
    @PostMapping("/demo/unknown-pattern")
    public String demoUnknownPattern() {
        SystemState state = new SystemState(
                Instant.now(),
                Map.of("customerClient", SystemState.CircuitBreakerState.HALF_OPEN),
                Map.of("shipment-service", 45L),
                Map.of("oms.customer.events.DLT", 380L)
        );
        return orchestrator.handle(state);
    }

    /**
     * Convenience endpoint: breakers CLOSED and lag fine (the outage is over), but a DLQ
     * backlog remains - the pattern replayDlqBatch exists for. No rule matches this (see
     * HealthyStateRule's DLQ depth check and OutboxLagRule's lag-only scope), so it forces
     * LLM escalation with a scenario that should read as "safe to replay" rather than
     * "page someone" - unlike demo/unknown-pattern, which deliberately includes a HALF_OPEN
     * breaker (a live, unresolved signal) and so tends to escalate to pageOncall instead.
     */
    @PostMapping("/demo/dlq-backlog-recovered")
    public String demoDlqBacklogRecovered() {
        SystemState state = new SystemState(
                Instant.now(),
                Map.of("customerClient", SystemState.CircuitBreakerState.CLOSED,
                        "productClient", SystemState.CircuitBreakerState.CLOSED),
                Map.of("customer-service", 5L),
                Map.of("oms.customer.events.DLT", 380L)
        );
        return orchestrator.handle(state);
    }

    @GetMapping("/coverage")
    public Map<String, Object> coverage() {
        return Map.of(
                "matchedByRuleEngine", ruleEngine.matchedCount(),
                "escalatedToLlm", ruleEngine.unmatchedCount(),
                "coverageRatio", ruleEngine.coverageRatio()
        );
    }
}
