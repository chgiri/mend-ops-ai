package com.giri.ai.mendops.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Queries each service's outbox table for the age (in seconds) of its oldest
 * unpublished event - a direct measure of publish lag.
 * <p>
 * Matches the real oms-main/product-service/customer-service outbox_events
 * schema: status is PENDING/PUBLISHED/FAILED (not a published boolean), and
 * both PENDING and FAILED count as backlog here - a FAILED event sitting
 * unpublished (e.g. retries exhausted) is exactly the kind of thing this
 * poller should surface, not just ones still waiting their first attempt.
 * <p>
 * The table name is optionally schema-qualified per source (see
 * OmsTelemetryProperties.OutboxSource.schema) - oms-main's outbox_events
 * lives in oms_messaging after its Phase 3 per-module schema split, while
 * product-service/customer-service (standalone single-purpose databases)
 * use the unqualified table in "public".
 */
@Component
public class OutboxLagPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxLagPoller.class);

    private final OutboxDataSourceRegistry registry;
    private final OmsTelemetryProperties properties;

    public OutboxLagPoller(OutboxDataSourceRegistry registry, OmsTelemetryProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public Map<String, Long> poll() {
        Map<String, Long> result = new LinkedHashMap<>();

        registry.templatesByService().forEach((serviceName, jdbcTemplate) -> {
            try {
                String query = buildQuery(serviceName);
                Double lagSeconds = jdbcTemplate.queryForObject(query, Double.class);
                result.put(serviceName, lagSeconds == null ? 0L : lagSeconds.longValue());
            } catch (Exception e) {
                log.warn("Failed to poll outbox lag for {}: {}", serviceName, e.getMessage());
            }
        });

        return result;
    }

    private String buildQuery(String serviceName) {
        String table = qualifiedTableName(serviceName);
        return """
                SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) AS lag_seconds
                FROM %s
                WHERE status <> 'PUBLISHED'
                """.formatted(table);
    }

    private String qualifiedTableName(String serviceName) {
        OmsTelemetryProperties.OutboxSource source =
                properties.outboxSources() == null ? null : properties.outboxSources().get(serviceName);

        String schema = source == null ? null : source.schema();
        return (schema == null || schema.isBlank()) ? "outbox_events" : schema + ".outbox_events";
    }
}
