package com.giri.ai.mendops.rules;

import java.util.List;

/**
 * Records every shadow-rule match so it can be reviewed after the fact, not
 * just watched live in logs. Pure storage - no logic beyond record/retrieve
 * - kept as an interface so a JPA-backed implementation (JpaShadowMatchHistory)
 * is a drop-in swap, mirroring RuleCandidateStore's proven seam pattern:
 * RuleEngine and RuleCandidateController only ever depend on this interface.
 */
public interface ShadowMatchHistory {

    void record(ShadowMatchRecord record);

    /** Most recent matches for a rule id, newest first - bounded per implementation (see JpaShadowMatchHistory). */
    List<ShadowMatchRecord> forRule(String ruleId);
}
