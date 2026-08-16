package com.giri.ai.mendops.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Alerts when a retry budget is still non-default - the active half of
 * RetryBudgetStatusService's flagging (that service only answers "what's
 * the current state" on demand; this is what makes drift actually surface
 * without anyone polling for it).
 * <p>
 * Deliberately stores no state of its own about what it's already alerted
 * on. The check interval IS the alert cadence: each run either finds a
 * still-non-default budget and pages again, or doesn't - re-alerting once
 * per interval while something stays drifted is the intended behavior here,
 * not noise to suppress. "since" (when the drift started) already comes
 * from ApprovalAuditRepository via RetryBudgetStatusService - not new state
 * this class needs to track. If you want a longer grace period before the
 * FIRST alert than the interval between repeat alerts, that would need
 * tracking "already alerted for this drift episode" - not needed for the
 * simpler "same interval both ways" behavor built here.
 */
@Component
public class RetryBudgetDriftMonitor {

    private static final Logger log = LoggerFactory.getLogger(RetryBudgetDriftMonitor.class);

    private final RetryBudgetStatusService statusService;
    private final PagingNotifier pagingNotifier;

    public RetryBudgetDriftMonitor(RetryBudgetStatusService statusService, PagingNotifier pagingNotifier) {
        this.statusService = statusService;
        this.pagingNotifier = pagingNotifier;
    }

    @Scheduled(fixedDelayString = "${mendops.remediation.retry-budget.drift-alert-interval-ms:43200000}")
    void checkForDrift() {
        for (RetryBudgetStatus status : statusService.currentStatus()) {
            if (!status.nonDefault()) {
                continue;
            }

            String age = status.since() == null ? "unknown duration"
                    : Duration.between(status.since(), Instant.now()).toHours() + "h";

            String summary = "Retry budget for " + status.serviceName() + " is " + status.currentMaxAttempts()
                    + " (default " + status.defaultMaxAttempts() + ") - drifted for " + age
                    + ". Was this meant to be permanent?";

            log.warn("[RETRY-BUDGET-DRIFT] {}", summary);
            pagingNotifier.page(summary);
        }
    }
}
