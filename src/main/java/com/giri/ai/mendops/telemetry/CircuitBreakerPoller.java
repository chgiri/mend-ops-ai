package com.giri.ai.mendops.telemetry;

import tools.jackson.databind.JsonNode;
import com.giri.ai.mendops.model.SystemState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Polls oms-main's {@code /actuator/circuitbreakers} endpoint for Resilience4j
 * circuit breaker states.
 * <p>
 * Deliberately NOT reading {@code /actuator/health} - on Spring Boot 4,
 * resilience4j's circuit breaker health indicator doesn't surface under
 * {@code components.circuitBreakers} at all (confirmed against oms-main's
 * live response), a known upstream issue:
 * https://github.com/resilience4j/resilience4j/issues/2350
 * {@code /actuator/circuitbreakers} is unaffected and was confirmed working
 * directly against oms-main:
 * <pre>{@code
 * {"circuitBreakers":{"productClient":{"state":"CLOSED",...},"customerClient":{"state":"CLOSED",...}}}
 * }</pre>
 * Requires {@code management.endpoints.web.exposure.include} to list
 * {@code circuitbreakers} on oms-main's side (it isn't exposed by default).
 * <p>
 * NOTE: uses {@code tools.jackson.databind.JsonNode}, not the Jackson 2
 * {@code com.fasterxml.jackson.databind.JsonNode} - Spring Boot 4 defaults to
 * Jackson 3, whose core/databind packages moved from com.fasterxml.jackson to
 * tools.jackson (annotations stayed at com.fasterxml.jackson.annotation).
 * RestClient's Jackson 3 message converter can't deserialize into a Jackson 2
 * type at all, which surfaces as "Type definition error" rather than a normal
 * parse failure - worth remembering if this bites again elsewhere in the project.
 */
@Component
public class CircuitBreakerPoller {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerPoller.class);

    private final RestClient restClient;
    private final OmsTelemetryProperties properties;

    public CircuitBreakerPoller(OmsTelemetryProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    public Map<String, SystemState.CircuitBreakerState> poll() {
        Map<String, SystemState.CircuitBreakerState> result = new LinkedHashMap<>();

        OmsTelemetryProperties.CircuitBreakers config = properties.circuitBreakers();
        if (config == null || config.actuatorBaseUrl() == null) {
            return result;
        }
        List<String> names = config.names() == null ? List.of() : config.names();

        try {
            JsonNode response = restClient.get()
                    .uri(config.actuatorBaseUrl() + "/actuator/circuitbreakers")
                    .retrieve()
                    .body(JsonNode.class);

            for (String name : names) {
                SystemState.CircuitBreakerState state = extractState(response, name);
                if (state != null) {
                    result.put(name, state);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to poll circuit breaker state from {}: {}",
                    config.actuatorBaseUrl(), e.getMessage());
        }

        return result;
    }

    private SystemState.CircuitBreakerState extractState(JsonNode response, String circuitBreakerName) {
        JsonNode stateNode = response
                .path("circuitBreakers").path(circuitBreakerName)
                .path("state");

        if (stateNode.isMissingNode() || stateNode.isNull()) {
            log.debug("No circuit breaker entry found for '{}'", circuitBreakerName);
            return null;
        }

        try {
            return SystemState.CircuitBreakerState.valueOf(stateNode.asText());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognized circuit breaker state '{}' for '{}'",
                    stateNode.asText(), circuitBreakerName);
            return null;
        }
    }
}