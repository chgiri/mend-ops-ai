package com.giri.ai.mendops.remediation;

import java.time.Instant;

/**
 * One instance name's retry-budget state, as reported by
 * RetryBudgetStatusService.currentStatus().
 *
 * @param serviceName        Resilience4j instance name (e.g. "productClient").
 * @param defaultMaxAttempts configured steady-state value
 *                           (mendops.remediation.retry-budget.default-max-attempts.*) -
 *                           null if never configured for this service, in
 *                           which case nonDefault can't be determined.
 * @param currentMaxAttempts the maxAttempts from the most recently APPROVED
 *                           adjustRetryBudget for this service - null if it
 *                           has never been changed via this system (still
 *                           presumably at whatever oms-main's own default is,
 *                           which this app has no visibility into other than
 *                           defaultMaxAttempts above).
 * @param nonDefault         true only when both values are known AND differ -
 *                           false (not "unknown") whenever either is null,
 *                           so a missing default never reads as a false alarm.
 * @param since              when the current value was approved - null if
 *                           currentMaxAttempts is null.
 */
public record RetryBudgetStatus(
        String serviceName,
        Integer defaultMaxAttempts,
        Integer currentMaxAttempts,
        boolean nonDefault,
        Instant since
) {
}
