package com.giri.ai.mendops.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * Config for the telemetry polling layer. Bound from {@code mendops.telemetry.*}
 * in application.properties.
 * <p>
 * NOTE: property names/shape here are a reasonable starting point, not verified
 * against a live deployment - see README's telemetry section for the exact
 * properties this expects, and adjust to match your actual oms-main /
 * product-service / customer-service hosts, ports, and DB credentials.
 */
@ConfigurationProperties(prefix = "mendops.telemetry")
public record OmsTelemetryProperties(
        CircuitBreakers circuitBreakers,
        Map<String, OutboxSource> outboxSources,
        Kafka kafka,
        long pollIntervalMs
) {

    public OmsTelemetryProperties {
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 15_000;
        }
    }

    /**
     * @param actuatorBaseUrl base URL of oms-main's actuator endpoint, e.g. http://localhost:8080
     * @param names           circuit breaker instance names to poll, e.g. productClient, customerClient
     */
    public record CircuitBreakers(String actuatorBaseUrl, List<String> names) {
    }

    /**
     * One entry per service that owns its own outbox table (oms-main, product-service,
     * customer-service). Each gets its own JDBC connection pool - see OutboxDataSourceRegistry.
     *
     * @param schema optional schema to qualify the table name with, e.g. "oms_messaging" for
     *               oms-main (whose outbox_events lives outside "public" after its Phase 3
     *               schema split). Leave unset for sources where the table is unqualified/public
     *               (product-service, customer-service).
     */
    public record OutboxSource(String jdbcUrl, String username, String password, String schema) {
    }

    /**
     * @param bootstrapServers Kafka bootstrap servers
     * @param dlqTopics        dead-letter topic names to check depth on, e.g. oms.customer.events.DLT
     */
    public record Kafka(String bootstrapServers, List<String> dlqTopics) {
    }
}
