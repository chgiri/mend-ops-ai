package com.giri.ai.mendops.telemetry;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds one small connection pool per entry in {@code mendops.telemetry.outbox-sources}
 * (oms-main, product-service, customer-service each own their outbox table in a
 * separate database - see project notes) and exposes a JdbcTemplate per service name.
 * <p>
 * Kept deliberately small: this service only ever reads outbox lag, so a tiny pool
 * (max 2 connections) per source is enough and avoids competing with each service's
 * own application traffic.
 */
@Component
public class OutboxDataSourceRegistry {

    private final Map<String, JdbcTemplate> templatesByService = new ConcurrentHashMap<>();

    public OutboxDataSourceRegistry(OmsTelemetryProperties properties) {
        if (properties.outboxSources() == null) {
            return;
        }
        properties.outboxSources().forEach((serviceName, source) -> {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(source.jdbcUrl());
            config.setUsername(source.username());
            config.setPassword(source.password());
            config.setMaximumPoolSize(2);
            config.setPoolName("outbox-lag-" + serviceName);
            config.setReadOnly(true);

            HikariDataSource dataSource = new HikariDataSource(config);
            templatesByService.put(serviceName, new JdbcTemplate(dataSource));
        });
    }

    public Map<String, JdbcTemplate> templatesByService() {
        return templatesByService;
    }
}
