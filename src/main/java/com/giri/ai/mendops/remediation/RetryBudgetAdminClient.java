package com.giri.ai.mendops.remediation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Real implementation behind RemediationTools.adjustRetryBudget.
 * <p>
 * "productClient"/"customerClient" (the serviceName/targetService values this
 * receives) are Resilience4j instance names for oms-main's own outbound
 * clients, not something living inside product-service/customer-service
 * themselves - those don't implement Resilience4j at all (see e.g.
 * CustomerClientImpl in oms-main, which builds its Retry/CircuitBreaker from
 * RetryRegistry/CircuitBreakerRegistry keyed by "customerClient"). So the
 * RetryRegistry this needs to reach lives in oms-main's process, same as
 * where CircuitBreakerPoller already reads circuit breaker state from.
 * Resilience4j's own actuator endpoints are read-only, so this expects
 * oms-main to expose a small admin endpoint of its own:
 * <pre>{@code
 * POST {adminBaseUrl}/internal/resilience/retry-budget
 * {"clientName": "productClient", "maxAttempts": 5}
 * }</pre>
 * configured per Resilience4j instance name under
 * {@code mendops.remediation.retry-budget.admin-base-url.<instanceName>} -
 * in practice both instance names point at the same oms-main host. If
 * oms-main doesn't implement that contract yet, this fails loudly rather
 * than silently pretending the change took effect.
 */
@Component
public class RetryBudgetAdminClient {

    private static final Logger log = LoggerFactory.getLogger(RetryBudgetAdminClient.class);
    private static final String ADMIN_PATH = "/internal/resilience/retry-budget";

    private final Map<String, String> adminBaseUrlByService;
    private final RestClient restClient;

    public RetryBudgetAdminClient(RemediationProperties properties) {
        this.adminBaseUrlByService = (properties.retryBudget() == null
                || properties.retryBudget().adminBaseUrl() == null)
                ? Map.of()
                : properties.retryBudget().adminBaseUrl();
        this.restClient = RestClient.create();
    }

    public void adjust(String targetService, int maxAttempts) {
        String adminBaseUrl = adminBaseUrlByService.get(targetService);
        if (adminBaseUrl == null || adminBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "No admin base URL configured for service '" + targetService + "' under "
                            + "mendops.remediation.retry-budget.admin-base-url." + targetService
                            + " - cannot adjust its retry budget.");
        }

        log.info("Calling {} to set retry budget for {} to {} attempts",
                adminBaseUrl + ADMIN_PATH, targetService, maxAttempts);

        restClient.post()
                .uri(adminBaseUrl + ADMIN_PATH)
                .body(Map.of("clientName", targetService, "maxAttempts", maxAttempts))
                .retrieve()
                .toBodilessEntity();
    }
}
