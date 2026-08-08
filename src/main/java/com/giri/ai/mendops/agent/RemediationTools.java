package com.giri.ai.mendops.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Real, scoped actions the LLM is allowed to invoke via Spring AI tool
 * calling. Each method here executes a genuine remediation - nothing about
 * *performing* the action needs an LLM, only the *decision* of which one
 * to call and when (see project notes on rule-engine vs. LLM division).
 * <p>
 * v1: logging stand-ins for the real integrations (Kafka admin client for
 * DLQ replay, Resilience4j registry for retry-budget tuning, a paging
 * webhook). Guardrails (blast-radius limits, human approval for anything
 * risky) belong in front of these calls, not inside them - see
 * AgentOrchestrator.
 */
@Component
public class RemediationTools {

    private static final Logger log = LoggerFactory.getLogger(RemediationTools.class);

    @Tool(description = "Replay a batch of messages from a dead-letter queue topic back onto its "
            + "source topic. Use when messages failed processing but the underlying cause has "
            + "since been resolved.")
    public String replayDlqBatch(String topic, int count) {
        log.info("[TOOL] replayDlqBatch topic={} count={}", topic, count);
        // TODO: wire to real Kafka admin client against the DLQ topic.
        return "Replayed " + count + " messages from " + topic;
    }

    @Tool(description = "Temporarily widen a Resilience4j retry budget (max attempts) for a "
            + "named client, to ride out transient backpressure without tripping the circuit "
            + "breaker.")
    public String adjustRetryBudget(String serviceName, int maxAttempts) {
        log.info("[TOOL] adjustRetryBudget service={} maxAttempts={}", serviceName, maxAttempts);
        // TODO: wire to a live Resilience4j RetryRegistry config update.
        return "Retry budget for " + serviceName + " set to " + maxAttempts + " attempts";
    }

    @Tool(description = "Page the on-call engineer with an incident summary. Use for anything "
            + "outside the agent's confidence or authority to auto-remediate.")
    public String pageOncall(String summary) {
        log.info("[TOOL] pageOncall summary={}", summary);
        // TODO: wire to a real paging webhook (PagerDuty/Slack/etc).
        return "Paged on-call: " + summary;
    }
}
