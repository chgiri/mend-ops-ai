package com.giri.ai.mendops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * mend-ops-ai's OWN database - the approval/audit history table, not any of
 * the outbox-lag sources OutboxDataSourceRegistry reads from. Deliberately
 * separate: those are read-only credentials into oms-main/product-service/
 * customer-service's own databases, and this service has no business writing
 * to them.
 * <p>
 * Runs as its own dedicated Postgres container (mend-ops-postgres in
 * docker-compose.yml), not a sibling database on any of the OMS stack's own
 * Postgres containers - database/role are created automatically by the
 * postgres image itself on first boot, schema by db/init.sql (mounted into
 * /docker-entrypoint-initdb.d/), and validated (not owned) by Hibernate at
 * startup - see application.properties' spring.jpa.hibernate.ddl-auto.
 */
@ConfigurationProperties(prefix = "mendops.persistence")
public record MendOpsPersistenceProperties(String jdbcUrl, String username, String password) {
}
