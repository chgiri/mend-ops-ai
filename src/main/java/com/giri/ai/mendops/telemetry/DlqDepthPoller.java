package com.giri.ai.mendops.telemetry;

import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Checks dead-letter topic depth via Kafka's AdminClient - no consumer group is
 * created, so this has no side effects on real consumers.
 * <p>
 * The AdminClient is created once here and reused across every poll cycle,
 * not recreated per poll - AdminClient is designed to be long-lived and holds
 * its own background connection/metadata-refresh thread. Recreating it every
 * cycle (the original v1 approach) meant re-bootstrapping a connection from
 * scratch every 15s by default, which is both wasteful and - if the broker is
 * unreachable - produces a continuous stream of "Rebootstrapping" log noise
 * instead of Kafka's own client handling reconnection/backoff internally.
 * <p>
 * NOTE: this reports each partition's latest (end) offset summed across the topic
 * as a depth <b>approximation</b>, not "unconsumed message count" - end offset
 * keeps growing even for messages already handled. If you need true unconsumed
 * depth, this would need to subtract each DLQ consumer group's committed offsets
 * (via AdminClient.listConsumerGroupOffsets) - left out of v1 to keep this simple;
 * revisit once a real DLQ-reprocessing consumer group exists to compare against.
 */
@Component
public class DlqDepthPoller {

    private static final Logger log = LoggerFactory.getLogger(DlqDepthPoller.class);

    private final OmsTelemetryProperties.Kafka config;
    private final AdminClient adminClient;

    public DlqDepthPoller(OmsTelemetryProperties properties) {
        this.config = properties.kafka();

        if (config == null || config.bootstrapServers() == null || config.dlqTopics() == null) {
            this.adminClient = null;
            return;
        }

        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers());
        adminProps.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        this.adminClient = AdminClient.create(adminProps);
    }

    public Map<String, Long> poll() {
        Map<String, Long> result = new LinkedHashMap<>();

        if (adminClient == null) {
            return result;
        }

        for (String topic : config.dlqTopics()) {
            try {
                long depth = topicEndOffsetSum(topic);
                result.put(topic, depth);
            } catch (Exception e) {
                log.warn("Failed to poll DLQ depth for topic {}: {}", topic, e.getMessage());
            }
        }

        return result;
    }

    private long topicEndOffsetSum(String topic) throws Exception {
        List<TopicPartition> partitions = adminClient.describeTopics(List.of(topic))
                .allTopicNames().get(10, TimeUnit.SECONDS)
                .get(topic).partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        partitions.forEach(tp -> request.put(tp, OffsetSpec.latest()));

        ListOffsetsResult result = adminClient.listOffsets(request);

        long sum = 0;
        for (TopicPartition tp : partitions) {
            ListOffsetsResult.ListOffsetsResultInfo info =
                    result.partitionResult(tp).get(10, TimeUnit.SECONDS);
            sum += info.offset();
        }
        return sum;
    }

    @PreDestroy
    public void shutdown() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
}