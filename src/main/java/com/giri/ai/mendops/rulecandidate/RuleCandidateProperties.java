package com.giri.ai.mendops.rulecandidate;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param recurrenceThreshold how many times the same anomalous fact must
 *                            recur (open then resolve) before
 *                            RuleCandidateDraftingService asks the LLM to
 *                            draft a candidate for it. Defaults to 3 -
 *                            deliberately not 1, per the recurrence-based
 *                            (not single-incident) triggering decision.
 */
@ConfigurationProperties(prefix = "mendops.rule-candidate")
public record RuleCandidateProperties(int recurrenceThreshold) {

    public RuleCandidateProperties {
        if (recurrenceThreshold <= 0) {
            recurrenceThreshold = 3;
        }
    }
}
