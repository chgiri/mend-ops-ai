package com.giri.ai.mendops.rules;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;

/**
 * A single deterministic rule: "does this SystemState match a known failure
 * pattern, and if so, what should be done about it."
 * <p>
 * v1 keeps this as a plain typed interface rather than reaching for Drools -
 * see project notes. Once the rule count grows unwieldy, this is the seam
 * to swap in a real rules engine without touching callers (RuleEngine is
 * the only consumer of this interface).
 */
public interface RemediationRule {

    /** Short, stable identifier - used in logs, audit trail, and rule-promotion review. */
    String id();

    /** Human-readable description of the failure pattern this rule recognizes. */
    String description();

    /** Whether this rule's failure pattern is present in the given state. */
    boolean matches(SystemState state);

    /** The action to take when matches() returns true. Only called if matches() is true. */
    RemediationAction actionFor(SystemState state);
}
