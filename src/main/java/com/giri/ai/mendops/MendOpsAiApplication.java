package com.giri.ai.mendops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.giri.ai.mendops.telemetry.OmsTelemetryProperties;
import com.giri.ai.mendops.remediation.RemediationProperties;
import com.giri.ai.mendops.remediation.OmsAuthProperties;

// DataSourceAutoConfiguration excluded: there is no single primary datasource for this
// service - OutboxDataSourceRegistry builds one DataSource per upstream service (oms-main,
// product-service, customer-service) from mendops.telemetry.outbox-sources instead.
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({OmsTelemetryProperties.class, RemediationProperties.class, OmsAuthProperties.class})
public class MendOpsAiApplication {

    public static void main(String[] args) {
        SpringApplication.run(MendOpsAiApplication.class, args);
    }
}