package com.giri.ai.mendops.remediation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * Config for the *real* integrations behind RemediationTools' three actions,
 * bound from {@code mendops.remediation.*}. Separate from
 * {@code mendops.telemetry.*} (OmsTelemetryProperties) since these are the
 * write-side counterpart - telemetry only ever reads.
 */
@ConfigurationProperties(prefix = "mendops.remediation")
public record RemediationProperties(
        Dlq dlq,
        RetryBudget retryBudget,
        Paging paging
) {

    /**
     * @param bootstrapServers   Kafka bootstrap servers. Falls back to
     *                           {@code mendops.telemetry.kafka.bootstrap-servers}
     *                           at injection time if unset here - see
     *                           DlqReplayService.
     * @param sourceTopicOverride explicit DLQ-topic -> source-topic mapping for
     *                           topics that don't follow the
     *                           {@code <source-topic>.DLT} convention Spring
     *                           Kafka's DeadLetterPublishingRecoverer uses by
     *                           default.
     * @param consumerGroupId    dedicated consumer group used only for replay
     *                           reads, so offsets committed here never collide
     *                           with a real DLQ-reprocessing consumer.
     */
    public record Dlq(String bootstrapServers, Map<String, String> sourceTopicOverride, String consumerGroupId) {
        public Dlq {
            if (consumerGroupId == null || consumerGroupId.isBlank()) {
                consumerGroupId = "mend-ops-ai-dlq-replay";
            }
        }
    }

    /**
     * Per-service admin base URL (the target's management port, e.g.
     * {@code http://localhost:8081} for oms-main) to call to change a live
     * retry budget. There is no standard Resilience4j actuator endpoint for
     * this (its actuator endpoints are read-only) - each target service is
     * expected to expose a custom actuator endpoint at
     * {@code POST {adminBaseUrl}/actuator/retrybudget} with a JSON body
     * {@code {"clientName": "...", "maxAttempts": N}}. Keyed by the same
     * targetService name RemediationTools receives.
     */
    public record RetryBudget(Map<String, String> adminBaseUrl) {
    }

    /**
     * @param webhookUrl Slack-incoming-webhook-style URL, POSTed a JSON body
     *                   {@code {"text": "..."}}. Works unmodified with a Slack
     *                   incoming webhook; swap the body shape in PagingNotifier
     *                   if pointing this at PagerDuty/Opsgenie instead.
     */
    public record Paging(String webhookUrl) {
    }
}
