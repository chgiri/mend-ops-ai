package com.giri.ai.mendops;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RemediationRule;
import com.giri.ai.mendops.rules.RuleEngine;
import com.giri.ai.mendops.rules.ShadowMatchHistory;
import com.giri.ai.mendops.rules.ShadowMatchRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineTest {

    private static ShadowMatchHistory noOpShadowMatchHistory() {
        return new ShadowMatchHistory() {
            public void record(ShadowMatchRecord record) { }
            public List<ShadowMatchRecord> forRule(String ruleId) { return List.of(); }
        };
    }

    @Test
    void matchedRuleIsReturnedAndCounted() {
        RemediationRule alwaysMatches = new RemediationRule() {
            public String id() { return "always-matches"; }
            public String description() { return "test rule"; }
            public boolean matches(SystemState state) { return true; }
            public RemediationAction actionFor(SystemState state) {
                return new RemediationAction("diag", RemediationAction.ActionType.NO_ACTION,
                        "svc", RemediationAction.Source.RULE_ENGINE, Map.of());
            }
        };
        RuleEngine engine = new RuleEngine(List.of(alwaysMatches), noOpShadowMatchHistory());

        SystemState state = new SystemState(Instant.now(), Map.of(), Map.of(), Map.of());
        var result = engine.evaluate(state);

        assertThat(result).isPresent();
        assertThat(engine.matchedCount()).isEqualTo(1);
        assertThat(engine.unmatchedCount()).isEqualTo(0);
        assertThat(engine.coverageRatio()).isEqualTo(1.0);
    }

    @Test
    void noMatchIsCountedAsEscalation() {
        RemediationRule neverMatches = new RemediationRule() {
            public String id() { return "never-matches"; }
            public String description() { return "test rule"; }
            public boolean matches(SystemState state) { return false; }
            public RemediationAction actionFor(SystemState state) { return null; }
        };
        RuleEngine engine = new RuleEngine(List.of(neverMatches), noOpShadowMatchHistory());

        SystemState state = new SystemState(Instant.now(), Map.of(), Map.of(), Map.of());
        var result = engine.evaluate(state);

        assertThat(result).isEmpty();
        assertThat(engine.matchedCount()).isEqualTo(0);
        assertThat(engine.unmatchedCount()).isEqualTo(1);
        assertThat(engine.coverageRatio()).isEqualTo(0.0);
    }
}
