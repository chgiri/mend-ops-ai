package com.giri.ai.mendops.controller;

import com.giri.ai.mendops.agent.ApprovalGate;
import com.giri.ai.mendops.agent.PendingApproval;
import com.giri.ai.mendops.model.RemediationAction;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/agent/approvals")
public class ApprovalController {

    private final ApprovalGate approvalGate;

    public ApprovalController(ApprovalGate approvalGate) {
        this.approvalGate = approvalGate;
    }

    /** View-only projection of PendingApproval - the real object holds a Callable, not serializable. */
    public record ApprovalView(
            String id,
            Instant createdAt,
            RemediationAction.ActionType actionType,
            String description,
            PendingApproval.Status status,
            Instant resolvedAt,
            String executionResult,
            String failureReason
    ) {
        static ApprovalView of(PendingApproval approval) {
            return new ApprovalView(
                    approval.id(), approval.createdAt(), approval.actionType(), approval.description(),
                    approval.status(), approval.resolvedAt(), approval.executionResult(),
                    approval.failureReason()
            );
        }
    }

    @GetMapping
    public List<ApprovalView> list(@RequestParam(required = false) Boolean pendingOnly) {
        var approvals = Boolean.TRUE.equals(pendingOnly) ? approvalGate.pending() : approvalGate.all();
        return approvals.stream().map(ApprovalView::of).toList();
    }

    @GetMapping("/{id}")
    public ApprovalView get(@PathVariable String id) {
        return ApprovalView.of(lookup(id));
    }

    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable String id) {
        try {
            approvalGate.approve(id);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Approved action failed to execute: " + e.getMessage());
        }
        return ApprovalView.of(lookup(id));
    }

    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable String id) {
        try {
            approvalGate.reject(id);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return ApprovalView.of(lookup(id));
    }

    private PendingApproval lookup(String id) {
        try {
            return approvalGate.get(id);
        } catch (java.util.NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
