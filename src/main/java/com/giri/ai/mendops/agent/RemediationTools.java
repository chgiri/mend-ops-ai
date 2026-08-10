package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.remediation.PagingNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real, scoped actions the LLM is allowed to invoke via Spring AI tool
 * calling. Nothing about *performing* a remediation needs an LLM, only the
 * *decision* of which one to call and when (see project notes on rule-engine
 * vs. LLM division).
 * <p>
 * replayDlqBatch and adjustRetryBudget are gated behind ApprovalGate: calling
 * them does NOT run the real action - it records a PendingApproval as DATA
 * (an actionType + a params map, not a Callable) and returns only an
 * id/acknowledgement to the LLM. The real Kafka/admin-endpoint call only
 * happens if a human approves it via ApprovalController, dispatched through
 * RemediationActionExecutor from that stored data - which is also what makes
 * a still-PENDING approval resumable after a restart (see PendingApproval's
 * Javadoc). pageOncall is NOT gated - paging is a notification, not a
 * destructive action, so it still executes immediately and doesn't need to
 * be resumable, matching the original guardrail design (every ActionType
 * except PAGE_ONCALL needs approval).
 * <p>
 * This class no longer depends on DlqReplayService/RetryBudgetAdminClient
 * directly (PagingNotifier is still needed for the ungated pageOncall) - the
 * real dispatch for gated actions now lives in RemediationActionExecutorImpl,
 * reachable purely from the (actionType, params) data ApprovalGate persists,
 * independent of whether this class - or even this process - is still the
 * one handling the eventual approve() call.
 */
@Component
public class RemediationTools {

    private static final Logger log = LoggerFactory.getLogger(RemediationTools.class);

    private final ApprovalGate approvalGate;
    private final PagingNotifier pagingNotifier;

    public RemediationTools(ApprovalGate approvalGate, PagingNotifier pagingNotifier) {
        this.approvalGate = approvalGate;
        this.pagingNotifier = pagingNotifier;
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
                Map.of("topic", topic, "count", Integer.toString(count))
        );
        return "Proposed and awaiting human approval (approvalId=" + id + "). "
                + description + ". No messages have been replayed yet.";
    }

    @Tool(description = "Propose temporarily widening a Resilience4j retry budget (max attempts) "
            + "for a named client, to ride out transient backpressure without tripping the "
            + "circuit breaker. This does NOT execute immediately - it is queued for human "
            + "approval and only runs once approved. serviceName MUST exactly match one of the "
            + "circuit breaker names shown in the current system state (e.g. \"productClient\", "
            + "\"customerClient\") - it is case-sensitive and is not a general service label like "
            + "\"product-service\".")
    public String adjustRetryBudget(String serviceName, int maxAttempts) {
        String description = "Set retry budget for " + serviceName + " to " + maxAttempts + " attempts";
        String id = approvalGate.propose(
                RemediationAction.ActionType.ADJUST_RETRY_BUDGET,
                description,
                Map.of("serviceName", serviceName, "maxAttempts", Integer.toString(maxAttempts))
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
        pagingNotifier.page(summary);
        return "Paged on-call: " + summary;
    }
}
