package com.giri.ai.mendops.rules;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Records every shadow-rule match so it can be reviewed after the fact, not
 * just watched live in logs - the actual gap this class closes. Bounded to
 * the most recent MAX_RECORDS_PER_RULE matches per rule id, oldest evicted
 * first, so a long-running shadow period doesn't grow unbounded.
 * <p>
 * In-memory only, same trade-off RuleCandidateStore/ApprovalGate originally
 * accepted before they got JPA persistence - lost on restart. Deliberately
 * scoped by rule id (not tied to the rulecandidate package at all): RuleEngine
 * evaluates generic RemediationRule instances and has no dependency on
 * RuleCandidate - keeping that dependency direction clean is exactly why
 * this lives in the rules package instead of rulecandidate.
 */
@Component
public class ShadowMatchHistory {

    private static final int MAX_RECORDS_PER_RULE = 50;

    private final Map<String, ConcurrentLinkedDeque<ShadowMatchRecord>> recordsByRuleId = new ConcurrentHashMap<>();

    public void record(ShadowMatchRecord record) {
        ConcurrentLinkedDeque<ShadowMatchRecord> deque =
                recordsByRuleId.computeIfAbsent(record.ruleId(), id -> new ConcurrentLinkedDeque<>());
        deque.addLast(record);
        while (deque.size() > MAX_RECORDS_PER_RULE) {
            deque.pollFirst();
        }
    }

    public List<ShadowMatchRecord> forRule(String ruleId) {
        ConcurrentLinkedDeque<ShadowMatchRecord> deque = recordsByRuleId.get(ruleId);
        return deque == null ? List.of() : List.copyOf(deque);
    }
}
