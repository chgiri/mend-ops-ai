package com.giri.ai.mendops.remediation;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Logs in to oms-main as mend-ops-ai's own SERVICE-role account (see
 * oms-main's ServiceAccountSeeder) and caches the resulting JWT, refreshing
 * it before it expires rather than logging in on every call. Login itself
 * only needs to happen roughly once per token lifetime (oms-main's default
 * is 24h, per its AuthResponse.expiresInMs) - not once per retry-budget
 * change.
 * <p>
 * Deliberately synchronized rather than double-checked-locking on a volatile
 * field: this is called immediately before an already-slow network call
 * (RetryBudgetAdminClient's POST to oms-main), not a hot path, so the extra
 * safety of a single lock is worth more here than shaving lock contention
 * that will never actually happen in practice.
 */
@Component
public class OmsAuthClient {

    private static final Duration REFRESH_SAFETY_BUFFER = Duration.ofSeconds(30);
    private static final String LOGIN_PATH = "/api/v1/auth/login";

    private final RestClient restClient;
    private final OmsAuthProperties properties;

    private String cachedToken;
    private Instant expiresAt = Instant.EPOCH;

    public OmsAuthClient(OmsAuthProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /**
     * Returns a currently-valid bearer token, logging in (or re-logging in,
     * if the cached one is expired or about to be) as needed. Callers don't
     * need to know or care whether this triggers a real login call.
     */
    public synchronized String getBearerToken() {
        if (cachedToken != null && Instant.now().isBefore(expiresAt)) {
            return cachedToken;
        }
        login();
        return cachedToken;
    }

    private void login() {
        LoginResponse response = restClient.post()
                .uri(properties.baseUrl() + LOGIN_PATH)
                .body(Map.of("username", properties.username(), "password", properties.password()))
                .retrieve()
                .body(LoginResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException(
                    "oms-main login succeeded but returned no accessToken - unexpected response shape");
        }

        this.cachedToken = response.accessToken();
        // Subtract a safety buffer so a token that's about to expire mid-call gets
        // refreshed proactively on the NEXT call, rather than being handed out one
        // request too late and failing with a 401 the caller has to retry around.
        this.expiresAt = Instant.now().plusMillis(response.expiresInMs()).minus(REFRESH_SAFETY_BUFFER);
    }

    /** Matches oms-main's AuthResponse shape - only the fields actually used are declared. */
    private record LoginResponse(String accessToken, long expiresInMs) {
    }
}
