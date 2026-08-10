package com.giri.ai.mendops.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Manually builds the single DataSource bean Spring Data JPA needs for the
 * approval audit trail (ApprovalAuditEntity/Repository).
 * <p>
 * MendOpsAiApplication excludes DataSourceAutoConfiguration because
 * OutboxDataSourceRegistry already owns per-outbox-source pools built by
 * hand, not from {@code spring.datasource.*} - so there's no single
 * "primary" datasource for Spring Boot to auto-configure from properties.
 * That exclusion doesn't stop us defining our OWN DataSource @Bean here
 * though - HibernateJpaAutoConfiguration/JpaRepositoriesAutoConfiguration
 * (pulled in by spring-boot-starter-data-jpa) just need *a* DataSource bean
 * present in the context, however it got there. This is the only DataSource
 * bean in the app, so @Primary is just future-proofing, not resolving an
 * actual ambiguity today.
 * <p>
 * MendOpsPersistenceProperties is registered centrally in
 * MendOpsAiApplication alongside the app's other @ConfigurationProperties
 * classes, not via a local @EnableConfigurationProperties here.
 */
@Configuration
public class MendOpsDataSourceConfig {

    @Bean
    public DataSource dataSource(MendOpsPersistenceProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.jdbcUrl());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setPoolName("mendops-persistence");
        // Small pool - this is one service's own audit-writes, not a
        // high-throughput app database.
        config.setMaximumPoolSize(5);
        return new HikariDataSource(config);
    }
}
