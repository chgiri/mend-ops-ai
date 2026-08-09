package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.remediation.DlqReplayService;
import com.giri.ai.mendops.remediation.PagingNotifier;
import com.giri.ai.mendops.remediation.RetryBudgetAdminClient;
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
 * returns only an id/acknowledgement to the LLM. The real Kafka/admin-endpoint
 * call only happens if a human approves it via ApprovalController. pageOncall
 * is NOT gated - paging is a notification, not a destructive action, so it
 * still executes immediately, matching the original guardrail design (every
 * ActionType except PAGE_ONCALL needs approval).
 * <p>
 * The real integrations live in the {@code remediation} package
 * (DlqReplayService, RetryBudgetAdminClient, PagingNotifier) - this class
 * stays a thin @Tool-annotated adapter over them so the LLM-facing surface
 * (descriptions, ApprovalGate wiring) is easy to scan independently of the
 * integration details.
 */
@Component
public class RemediationTools {

    private static final Logger log = LoggerFactory.getLogger(RemediationTools.class);

    private final ApprovalGate approvalGate;
    private final DlqReplayService dlqReplayService;
    private final RetryBudgetAdminClient retryBudgetAdminClient;
    private final PagingNotifier pagingNotifier;

    public RemediationTools(ApprovalGate approvalGate, DlqReplayService dlqReplayService,
                             RetryBudgetAdminClient retryBudgetAdminClient, PagingNotifier pagingNotifier) {
        this.approvalGate = approvalGate;
        this.dlqReplayService = dlqReplayService;
        this.retryBudgetAdminClient = retryBudgetAdminClient;
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
                () -> {
                    int replayed = dlqReplayService.replayBatch(topic, count);
                    log.info("[TOOL:EXECUTED] replayDlqBatch topic={} requested={} replayed={}",
                            topic, count, replayed);
                    return "Replayed " + replayed + " of " + count + " requested messages from " + topic;
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
                    retryBudgetAdminClient.adjust(serviceName, maxAttempts);
                    log.info("[TOOL:EXECUTED] adjustRetryBudget service={} maxAttempts={}",
                            serviceName, maxAttempts);
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
        pagingNotifier.page(summary);
        return "Paged on-call: " + summary;
    }
}
