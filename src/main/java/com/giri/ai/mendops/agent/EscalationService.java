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
 * v1 intentionally does NOT let the LLM execute a remediation directly - the
 * tools it calls for replayDlqBatch/adjustRetryBudget only ever create a
 * PendingApproval (see ApprovalGate); the real action runs only once a human
 * approves it via ApprovalController. This class only produces a *proposed*
 * RemediationAction (or, for pageOncall, a real but non-destructive
 * notification); it does not execute anything risky on its own.
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
            given the known call graph (order creation depends on productClient and
            customerClient calls; outbox publishers feed Kafka topics consumed by other
            services). A CLOSED breaker for a service means calls to it are succeeding right
            now - treat that as a real recovery signal, not just an absence of alarm; a
            backlog (DLQ depth, outbox lag) alongside all-CLOSED breakers usually means the
            underlying cause has already resolved and what's left is cleanup, which is exactly
            what replayDlqBatch is for. A HALF_OPEN breaker with NO other symptoms (low lag, low
            DLQ depth) means the service is already recovering on its own and just needs a
            wider retry budget to get through the last bit of transient instability without
            tripping back to OPEN - that's what adjustRetryBudget is for, and it does not need
            a human in the loop just because the breaker isn't fully CLOSED yet. Reserve
            pageOncall for an OPEN breaker, a HALF_OPEN breaker WITH other symptoms (a real
            backlog, high lag - i.e. still actively failing, not just probing), or genuinely
            ambiguous signals - don't default to it just because a human could also handle it.
            Produce a concise diagnosis in plain English, then decide whether an automated
            remediation is appropriate or whether this should be escalated to a human. Prefer
            paging a human when you are not confident, when the situation could involve data
            loss, or when the correct action is ambiguous.
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
     * take, for the low-risk pageOncall tool call - a remediation.
     * <p>
     * Uses defaultTools directly, meaning the model can invoke any RemediationTools
     * method it decides on - safe because replayDlqBatch/adjustRetryBudget are
     * approval-gated at the tool-implementation level (see RemediationTools), not
     * because anything here restricts which tools the model can reach.
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
