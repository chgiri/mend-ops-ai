package com.giri.ai.mendops.agent;

import com.giri.ai.mendops.model.RemediationAction;

import java.util.Map;

/**
 * Dispatches a gated remediation action from DATA (actionType + a flat
 * String/String params map) to the real integration call, instead of
 * ApprovalGate holding a pre-captured Callable.
 * <p>
 * This is the piece that makes PendingApproval crash-safe resumable: since
 * both actionType and params are plain serializable data, ApprovalGate can
 * persist them, reload them after a restart, and call execute() again -
 * there's no closure over live beans to lose. See
 * RemediationActionExecutorImpl for the actual dispatch logic (Kafka replay /
 * retry-budget admin call) and RemediationTools for how params get built in
 * the first place.
 */
public interface RemediationActionExecutor {

    /**
     * Runs the real action. Throws if actionType isn't one this executor
     * knows how to run (PAGE_ONCALL/NO_ACTION never reach here - they're not
     * approval-gated) or if a required param is missing/malformed.
     */
    String execute(RemediationAction.ActionType actionType, Map<String, String> params) throws Exception;
}
