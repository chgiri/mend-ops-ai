package com.giri.ai.mendops.rules;

import java.time.Instant;

/**
 * One instance of a shadow rule matching a real poll - what it would have
 * done, without it actually happening. See ShadowMatchHistory.
 */
public record ShadowMatchRecord(String ruleId, Instant matchedAt, String diagnosis, String actionSummary) {
}
