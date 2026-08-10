package com.giri.ai.mendops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.giri.ai.mendops.telemetry.OmsTelemetryProperties;
import com.giri.ai.mendops.remediation.RemediationProperties;
import com.giri.ai.mendops.remediation.OmsAuthProperties;
import com.giri.ai.mendops.config.MendOpsPersistenceProperties;

// DataSourceAutoConfiguration excluded: there is no single primary datasource for this
// service to auto-configure from spring.datasource.* - OutboxDataSourceRegistry builds one
// read-only DataSource per upstream service (oms-main, product-service, customer-service)
// by hand from mendops.telemetry.outbox-sources instead. This exclusion does NOT mean there's
// no DataSource bean at all, though: MendOpsDataSourceConfig defines exactly one (mend-ops-ai's
// own persistence DB, for the approval audit trail) manually, the same way OutboxDataSourceRegistry
// does for its pools - see that class's Javadoc.
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({OmsTelemetryProperties.class, RemediationProperties.class,
        OmsAuthProperties.class, MendOpsPersistenceProperties.class})
public class MendOpsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MendOpsAiApplication.class, args);
    }
}