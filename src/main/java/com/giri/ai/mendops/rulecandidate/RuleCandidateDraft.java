package com.giri.ai.mendops.rulecandidate;

import com.giri.ai.mendops.model.RemediationAction;

/**
 * What the LLM is actually asked to decide when drafting a candidate -
 * deliberately narrow. The candidate's conditions and the action's target
 * identifier (which service/topic) are derived deterministically from the
 * recurring fact itself (see RuleCandidateDraftingService) - the LLM's job
 * is only the diagnosis, which action type fits, and the one tunable
 * numeric/text parameter that action needs. A flat set of nullable fields
 * (one per possible action) rather than a generic Map<String,String> -
 * more reliable for structured-output/JSON-schema binding across providers,
 * and consistent with this project's general approach of keeping the LLM
 * out of anything a deterministic path can handle instead (see e.g. why
 * RetryBudgetAdminClient validates serviceName before ever proposing it).
 */
public record RuleCandidateDraft(
        String diagnosis,
        RemediationAction.ActionType actionType,
        Integer maxAttempts,
        Integer replayCount,
        String pageSummary
) {
}
