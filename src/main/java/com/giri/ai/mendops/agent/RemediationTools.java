package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * Real, scoped actions the LLM is allowed to invoke via Spring AI tool
 * calling. Nothing about *performing* a remediation needs an LLM, only the
 * *decision* of which one to call and when (see project notes on rule-engine
 * vs. LLM division).
 * <p>
 * replayDlqBatch and adjustRetryBudget are gated behind ApprovalGate: calling
 * them does NOT run the real action - it records a PendingApproval and
 * returns only an id/acknowledgement to the LLM. The real Kafka/Resilience4j
 * call only happens if a human approves it via ApprovalController. pageOncall
 * is NOT gated - paging is a notification, not a destructive action, so it
 * still executes immediately, matching the original guardrail design (every
 * ActionType except PAGE_ONCALL needs approval).
 * <p>
 * v1: logging stand-ins for the real integrations (Kafka admin client for
 * DLQ replay, Resilience4j registry for retry-budget tuning, a paging
 * webhook) - the TODOs below are unchanged by adding the approval gate,
 * they're just now wrapped inside the Callable passed to ApprovalGate.
 */
@Component
public class RemediationTools {

    private static final Logger log = LoggerFactory.getLogger(RemediationTools.class);

    private final ApprovalGate approvalGate;

    public RemediationTools(ApprovalGate approvalGate) {
        this.approvalGate = approvalGate;
    }

    @Tool(description = "Propose replaying a batch of messages from a dead-letter queue topic "
            + "back onto its source topic. This does NOT execute immediately - it is queued for "
            + "human approval and only runs once approved. Use when messages failed processing "
            + "but the underlying cause has since been resolved.")
    public String replayDlqBatch(String topic, int count) {
        String description = "Replay " + count + " messages from " + topic;
        String id = approvalGate.propose(
                RemediationAction.ActionType.REPLAY_DLQ_BATCH,
                description,
                () -> {
                    log.info("[TOOL:EXECUTED] replayDlqBatch topic={} count={}", topic, count);
                    // TODO: wire to real Kafka admin client against the DLQ topic.
                    return "Replayed " + count + " messages from " + topic;
                }
        );
        return "Proposed and awaiting human approval (approvalId=" + id + "). "
                + description + ". No messages have been replayed yet.";
    }

    @Tool(description = "Propose temporarily widening a Resilience4j retry budget (max attempts) "
            + "for a named client, to ride out transient backpressure without tripping the "
            + "circuit breaker. This does NOT execute immediately - it is queued for human "
            + "approval and only runs once approved.")
    public String adjustRetryBudget(String serviceName, int maxAttempts) {
        String description = "Set retry budget for " + serviceName + " to " + maxAttempts + " attempts";
        String id = approvalGate.propose(
                RemediationAction.ActionType.ADJUST_RETRY_BUDGET,
                description,
                () -> {
                    log.info("[TOOL:EXECUTED] adjustRetryBudget service={} maxAttempts={}",
                            serviceName, maxAttempts);
                    // TODO: wire to a live Resilience4j RetryRegistry config update.
                    return "Retry budget for " + serviceName + " set to " + maxAttempts + " attempts";
                }
        );
        return "Proposed and awaiting human approval (approvalId=" + id + "). "
                + description + ". No retry budget has been changed yet.";
    }

    @Tool(description = "Page the on-call engineer with an incident summary. Executes immediately "
            + "- paging is a notification, not a destructive action, so it doesn't require "
            + "approval. Use for anything outside the agent's confidence or authority to "
            + "auto-remediate.")
    public String pageOncall(String summary) {
        log.info("[TOOL] pageOncall summary={}", summary);
        // TODO: wire to a real paging webhook (PagerDuty/Slack/etc).
        return "Paged on-call: " + summary;
    }
}
