package com.giri.ai.mendops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mend-ops-ai's OWN database - the approval/audit history table, not any of
 * the outbox-lag sources OutboxDataSourceRegistry reads from. Deliberately
 * separate: those are read-only credentials into oms-main/product-service/
 * customer-service's own databases, and this service has no business writing
 * to them. This is a database mend-ops-ai owns and writes to itself, e.g.
 * {@code mendops} on the same local Postgres instance those other three
 * already live on (create it once locally with {@code CREATE DATABASE
 * mendops;}) - or point this at wherever else you'd rather run it.
 */
@ConfigurationProperties(prefix = "mendops.persistence")
public record MendOpsPersistenceProperties(String jdbcUrl, String username, String password) {
}
