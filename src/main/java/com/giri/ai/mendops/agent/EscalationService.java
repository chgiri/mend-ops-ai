package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.SystemState;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * The expensive path: called only when RuleEngine finds no matching rule
 * for a given SystemState. Uses Spring AI's ChatClient with tool calling
 * (RemediationTools) so the model can both diagnose the failure in natural
 * language and select/parameterize a real remediation action.
 * <p>
 * v1 intentionally does NOT let the LLM call tools that act on production
 * without a human approval step in front of it - see AgentOrchestrator for
 * where that gate belongs. This class only produces a *proposed*
 * RemediationAction; it does not execute anything on its own.
 */
@Service
public class EscalationService {

    private static final String SYSTEM_PROMPT = """
            You are a site-reliability diagnosis assistant for an order management
            system built from microservices (oms-main, product-service, customer-service,
            shipment-service) communicating over Kafka with a transactional outbox pattern,
            and Resilience4j circuit breakers guarding synchronous calls between services.

            You are given a snapshot of live signals: circuit breaker states, outbox publish
            lag per service, and dead-letter queue depth per topic. A deterministic rule
            engine already checked this snapshot against known failure patterns and found no
            match - you are seeing this specifically BECAUSE it is a novel or ambiguous
            combination of signals.

            Reason about which services are affected and how the failure likely propagates
            given the known call graph (order creation depends on ProductClient and
            CustomerClient calls; outbox publishers feed Kafka topics consumed by other
            services). Produce a concise diagnosis in plain English, then decide whether an
            automated remediation is appropriate or whether this should be escalated to a
            human. Prefer paging a human when you are not confident, when the situation could
            involve data loss, or when the correct action is ambiguous.
            """;

    private final ChatClient chatClient;

    public EscalationService(ChatClient.Builder chatClientBuilder, RemediationTools remediationTools) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultTools(remediationTools)
                .build();
    }

    /**
     * Asks the LLM to diagnose an unmatched SystemState and propose - or directly
     * take, for low-risk tool calls like pageOncall - a remediation.
     * <p>
     * NOTE: v1 uses defaultTools directly, meaning the model can invoke any
     * RemediationTools method it decides on. Before this touches anything
     * beyond a demo/local environment, add an approval gate here for
     * actionTypes other than PAGE_ONCALL, per the guardrail design discussed
     * in project notes.
     */
    public String diagnoseAndAct(SystemState state) {
        String userMessage = """
                Current system state (captured at %s):
                Circuit breakers: %s
                Outbox publish lag (seconds): %s
                DLQ depth: %s

                Diagnose the likely root cause and take or propose the appropriate action.
                """.formatted(
                state.capturedAt(),
                state.circuitBreakers(),
                state.outboxLagSeconds(),
                state.dlqDepth()
        );

        return chatClient.prompt()
                .user(userMessage)
                .call()
                .content();
    }
}
