package com.giri.ai.mendops.controller;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.rulecandidate.RuleCandidate;
import com.giri.ai.mendops.rulecandidate.RuleCandidateReviewService;
import com.giri.ai.mendops.rulecandidate.RuleCandidateStore;
import com.giri.ai.mendops.rules.ShadowMatchHistory;
import com.giri.ai.mendops.rules.ShadowMatchRecord;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/agent/rule-candidates")
public class RuleCandidateController {

    private final RuleCandidateStore store;
    private final RuleCandidateReviewService reviewService;
    private final ShadowMatchHistory shadowMatchHistory;

    public RuleCandidateController(RuleCandidateStore store, RuleCandidateReviewService reviewService,
                                    ShadowMatchHistory shadowMatchHistory) {
        this.store = store;
        this.reviewService = reviewService;
        this.shadowMatchHistory = shadowMatchHistory;
    }

    /** View-only projection of RuleCandidate - matches ApprovalController's ApprovalView pattern. */
    public record CandidateView(
            String id,
            String sourceFact,
            int occurrenceCountAtDrafting,
            String diagnosis,
            List<RuleCandidate.Condition> conditions,
            RemediationAction.ActionType actionType,
            Map<String, String> actionParams,
            RuleCandidate.Status status,
            Instant createdAt,
            Instant resolvedAt
    ) {
        static CandidateView of(RuleCandidate candidate) {
            return new CandidateView(
                    candidate.id(), candidate.sourceFact(), candidate.occurrenceCountAtDrafting(),
                    candidate.diagnosis(), candidate.conditions(), candidate.actionType(),
                    candidate.actionParams(), candidate.status(), candidate.createdAt(), candidate.resolvedAt()
            );
        }
    }

    @GetMapping
    public List<CandidateView> list(@RequestParam(required = false) RuleCandidate.Status status) {
        List<RuleCandidate> candidates = status != null ? store.findByStatus(status) : store.findAll();
        return candidates.stream().map(CandidateView::of).toList();
    }

    @GetMapping("/{id}")
    public CandidateView get(@PathVariable String id) {
        return withNotFoundTranslation(() -> reviewService.get(id));
    }

    /**
     * Every recorded shadow match for this candidate (most recent
     * MAX_RECORDS_PER_RULE, see ShadowMatchHistory) - what the candidate
     * would have done, had it been LIVE, each time it matched real traffic
     * while in shadow. Meaningful for a candidate at any status (including
     * after promotion or rejection - the history isn't cleared on
     * transition, it's a record of what happened while it WAS in shadow).
     * A candidate that exists but was never shadow-approved, or hasn't
     * matched anything yet, returns an empty list, not a 404 - only an
     * unknown id 404s.
     */
    @GetMapping("/{id}/shadow-history")
    public List<ShadowMatchRecord> shadowHistory(@PathVariable String id) {
        withNotFoundTranslation(() -> reviewService.get(id));
        return shadowMatchHistory.forRule(id);
    }

    /** PENDING_REVIEW -> APPROVED_SHADOW. */
    @PostMapping("/{id}/approve")
    public CandidateView approve(@PathVariable String id) {
        return withTransitionTranslation(() -> reviewService.approveToShadow(id));
    }

    /** APPROVED_SHADOW -> LIVE. Always a separate, explicit call - never auto-promoted from shadow. */
    @PostMapping("/{id}/promote")
    public CandidateView promote(@PathVariable String id) {
        return withTransitionTranslation(() -> reviewService.promoteToLive(id));
    }

    /** PENDING_REVIEW or APPROVED_SHADOW -> REJECTED. */
    @PostMapping("/{id}/reject")
    public CandidateView reject(@PathVariable String id) {
        return withTransitionTranslation(() -> reviewService.reject(id));
    }

    private CandidateView withTransitionTranslation(Supplier<RuleCandidate> transition) {
        try {
            return CandidateView.of(transition.get());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private CandidateView withNotFoundTranslation(Supplier<RuleCandidate> lookup) {
        try {
            return CandidateView.of(lookup.get());
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
