package com.giri.ai.mendops.controller;

import com.giri.ai.mendops.remediation.RetryBudgetStatus;
import com.giri.ai.mendops.remediation.RetryBudgetStatusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only visibility into whether any retry budget is still widened from
 * a past adjustRetryBudget approval and was never put back - see
 * RetryBudgetStatusService's Javadoc for how "current" and "default" are
 * derived. Deliberately just an endpoint, not a scheduled alert/page - see
 * that service's Javadoc for why a dashboard isn't a prerequisite for this
 * to be useful (poll it, or wire your own monitoring to it).
 */
@RestController
@RequestMapping("/api/v1/agent/retry-budgets")
public class RetryBudgetStatusController {

    private final RetryBudgetStatusService statusService;

    public RetryBudgetStatusController(RetryBudgetStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    public List<RetryBudgetStatus> status() {
        return statusService.currentStatus();
    }
}
