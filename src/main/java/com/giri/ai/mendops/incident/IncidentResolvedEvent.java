package com.giri.ai.mendops.incident;

import java.time.Instant;

/**
 * Published by IncidentTracker every time a tracked fact resolves -
 * regardless of occurrence count. IncidentTracker deliberately doesn't know
 * or care what the recurrence threshold for drafting a rule candidate is;
 * that filtering happens entirely in whatever listens for this event
 * (RuleCandidateDraftingService), keeping IncidentTracker itself agnostic of
 * that downstream feature's config.
 */
public record IncidentResolvedEvent(
        String fact,
        int occurrenceCount,
        Instant firstSeenAt,
        Instant resolvedAt
) {
}
