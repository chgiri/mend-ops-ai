package com.giri.ai.mendops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mend-ops-ai's OWN database - the approval/audit history table, not any of
 * the outbox-lag sources OutboxDataSourceRegistry reads from. Deliberately
 * separate: those are read-only credentials into oms-main/product-service/
 * customer-service's own databases, and this service has no business writing
 * to them. This is a database mend-ops-ai owns and writes to itself: {@code
 * mendops}, a sibling database specifically on oms-main's own Postgres
 * container (service name "postgres" on oms-network, port 5432) - NOT
 * shared with product-service/customer-service, which each run their own
 * separate Postgres container. Create the database and a dedicated role
 * once locally (see application.properties' mendops.persistence.* comment
 * for the exact SQL) - or point this at wherever else you'd rather run it.
 */
@ConfigurationProperties(prefix = "mendops.persistence")
public record MendOpsPersistenceProperties(String jdbcUrl, String username, String password) {
}
