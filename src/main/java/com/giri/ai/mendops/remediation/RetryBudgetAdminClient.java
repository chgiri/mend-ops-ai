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
 * where CircuitBreakerPoller already reads circuit breaker state from -
 * both are actuator endpoints on oms-main's management port (8081 locally),
 * not its main app port.
 * <p>
 * Resilience4j's own built-in actuator endpoints are read-only, so oms-main
 * exposes a custom one as a genuine actuator {@code @Endpoint}/
 * {@code @WriteOperation} (RetryBudgetEndpoint, id "retrybudget") rather
 * than a plain REST controller - that's specifically what puts it on the
 * management port instead of the main one, matching the network-isolation
 * posture of the rest of actuator:
 * <pre>{@code
 * POST {adminBaseUrl}/actuator/retrybudget
 * {"clientName": "productClient", "maxAttempts": 5}
 * }</pre>
 * configured per Resilience4j instance name under
 * {@code mendops.remediation.retry-budget.admin-base-url.<instanceName>} -
 * in practice both instance names point at the same oms-main management-port
 * host, e.g. {@code http://localhost:8081}. If oms-main doesn't expose that
 * endpoint yet (or hasn't listed "retrybudget" under
 * {@code management.endpoints.web.exposure.include}), this fails loudly
 * rather than silently pretending the change took effect.
 * <p>
 * Authenticates as mend-ops-ai's SERVICE-role account via OmsAuthClient,
 * attaching "Authorization: Bearer &lt;token&gt;" on every call - this
 * endpoint is scoped to hasRole("SERVICE") on oms-main's side (not just
 * anyRequest().authenticated()), so an unauthenticated or wrong-role call
 * fails with 401/403 rather than silently succeeding.
 */
@Component
public class RetryBudgetAdminClient {

    private static final Logger log = LoggerFactory.getLogger(RetryBudgetAdminClient.class);
    private static final String ADMIN_PATH = "/actuator/retrybudget";

    private final Map<String, String> adminBaseUrlByService;
    private final RestClient restClient;
    private final OmsAuthClient omsAuthClient;

    public RetryBudgetAdminClient(RemediationProperties properties, OmsAuthClient omsAuthClient) {
        this.adminBaseUrlByService = (properties.retryBudget() == null
                || properties.retryBudget().adminBaseUrl() == null)
                ? Map.of()
                : properties.retryBudget().adminBaseUrl();
        this.restClient = RestClient.create();
        this.omsAuthClient = omsAuthClient;
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
                .header("Authorization", "Bearer " + omsAuthClient.getBearerToken())
                .body(Map.of("clientName", targetService, "maxAttempts", maxAttempts))
                .retrieve()
                .toBodilessEntity();
    }
}
