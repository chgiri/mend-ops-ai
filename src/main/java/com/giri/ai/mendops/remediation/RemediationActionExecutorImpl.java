package com.giri.ai.mendops.remediation;

import com.giri.ai.mendops.agent.RemediationActionExecutor;
import com.giri.ai.mendops.model.RemediationAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Real dispatch behind RemediationActionExecutor - re-derives the actual
 * DlqReplayService/RetryBudgetAdminClient call from stored (actionType,
 * params) data. This is what RemediationTools' Callable-based lambdas used
 * to do inline; moving it here (rather than back into RemediationTools) is
 * what lets ApprovalGate call execute() again after a restart without
 * needing RemediationTools itself involved at all - the params it stored are
 * self-sufficient.
 */
@Component
public class RemediationActionExecutorImpl implements RemediationActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(RemediationActionExecutorImpl.class);

    private final DlqReplayService dlqReplayService;
    private final RetryBudgetAdminClient retryBudgetAdminClient;

    public RemediationActionExecutorImpl(DlqReplayService dlqReplayService,
                                          RetryBudgetAdminClient retryBudgetAdminClient) {
        this.dlqReplayService = dlqReplayService;
        this.retryBudgetAdminClient = retryBudgetAdminClient;
    }

    @Override
    public String execute(RemediationAction.ActionType actionType, Map<String, String> params) throws Exception {
        return switch (actionType) {
            case REPLAY_DLQ_BATCH -> executeReplayDlqBatch(params);
            case ADJUST_RETRY_BUDGET -> executeAdjustRetryBudget(params);
            default -> throw new IllegalStateException(
                    "No executor for action type " + actionType + " - only REPLAY_DLQ_BATCH and "
                            + "ADJUST_RETRY_BUDGET are approval-gated/resumable; PAGE_ONCALL and "
                            + "NO_ACTION should never reach ApprovalGate at all.");
        };
    }

    private String executeReplayDlqBatch(Map<String, String> params) {
        String topic = requireParam(params, "topic");
        int count = requireIntParam(params, "count");

        int replayed = dlqReplayService.replayBatch(topic, count);
        log.info("[EXECUTED] replayDlqBatch topic={} requested={} replayed={}", topic, count, replayed);
        return "Replayed " + replayed + " of " + count + " requested messages from " + topic;
    }

    private String executeAdjustRetryBudget(Map<String, String> params) {
        String serviceName = requireParam(params, "serviceName");
        int maxAttempts = requireIntParam(params, "maxAttempts");

        retryBudgetAdminClient.adjust(serviceName, maxAttempts);
        log.info("[EXECUTED] adjustRetryBudget service={} maxAttempts={}", serviceName, maxAttempts);
        return "Retry budget for " + serviceName + " set to " + maxAttempts + " attempts";
    }

    private String requireParam(Map<String, String> params, String key) {
        String value = params.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required param '" + key + "' for this action");
        }
        return value;
    }

    private int requireIntParam(Map<String, String> params, String key) {
        String raw = requireParam(params, key);
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Param '" + key + "' must be an integer, got '" + raw + "'");
        }
    }
}
