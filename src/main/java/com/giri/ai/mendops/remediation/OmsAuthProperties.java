package com.giri.ai.mendops.remediation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials for mend-ops-ai's own SERVICE-role account on oms-main (see
 * oms-main's ServiceAccountSeeder), used to obtain a real JWT via the same
 * {@code POST /api/v1/auth/login} flow every other oms-main caller uses -
 * not a separate/parallel auth mechanism. See OmsAuthClient.
 * <p>
 * baseUrl is oms-main's MAIN app port (e.g. {@code http://localhost:8080}),
 * not the management port RemediationProperties.RetryBudget.adminBaseUrl
 * points at - login is a normal MVC controller under oms-main's global
 * {@code /api/v1} prefix, not actuator infrastructure.
 */
@ConfigurationProperties(prefix = "mendops.oms-auth")
public record OmsAuthProperties(String baseUrl, String username, String password) {
}
