package com.giri.ai.mendops.telemetry;

import com.giri.ai.mendops.remediation.RemediationProperties;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Checks dead-letter topic depth via Kafka's AdminClient - no consumer group
 * of its own is created for reading messages, so this has no side effects
 * on real consumers.
 * <p>
 * The AdminClient is created once here and reused across every poll cycle,
 * not recreated per poll - AdminClient is designed to be long-lived and holds
 * its own background connection/metadata-refresh thread. Recreating it every
 * cycle (the original v1 approach) meant re-bootstrapping a connection from
 * scratch every 15s by default, which is both wasteful and - if the broker is
 * unreachable - produces a continuous stream of "Rebootstrapping" log noise
 * instead of Kafka's own client handling reconnection/backoff internally.
 * <p>
 * Reports TRUE unconsumed depth, not a raw end-offset sum: end offset minus
 * DlqReplayService's own committed offset (mendops.remediation.dlq.consumer-group-id
 * - see RemediationProperties), falling back to the log's beginning offset
 * when that group has never committed for a partition (i.e. nothing has ever
 * been consumed, so every existing message counts as backlog). This works
 * specifically because DlqReplayService is the only thing that ever reads
 * from these topics in this system - a DLQ topic has no natural continuous
 * consumer in steady state, so "replayed" is exactly what "consumed" means
 * here. Depends on RemediationProperties (a new telemetry -> remediation
 * coupling that didn't exist before) rather than a second, independently
 * configured group-id property, specifically to avoid the two ever silently
 * drifting apart.
 */
@Component
public class DlqDepthPoller {

    private static final Logger log = LoggerFactory.getLogger(DlqDepthPoller.class);

    private final OmsTelemetryProperties.Kafka config;
    private final String dlqConsumerGroupId;
    private final AdminClient adminClient;

    public DlqDepthPoller(OmsTelemetryProperties properties, RemediationProperties remediationProperties) {
        this.config = properties.kafka();
        this.dlqConsumerGroupId = remediationProperties.dlq() != null
                ? remediationProperties.dlq().consumerGroupId() : null;

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
                long depth = topicUnconsumedDepth(topic);
                result.put(topic, depth);
            } catch (Exception e) {
                log.warn("Failed to poll DLQ depth for topic {}: {}", topic, e.getMessage());
            }
        }

        return result;
    }

    private long topicUnconsumedDepth(String topic) throws Exception {
        List<TopicPartition> partitions = adminClient.describeTopics(List.of(topic))
                .allTopicNames().get(10, TimeUnit.SECONDS)
                .get(topic).partitions().stream()
                .map(p -> new TopicPartition(topic, p.partition()))
                .toList();

        Map<TopicPartition, Long> endOffsets = fetchOffsets(partitions, OffsetSpec.latest());
        Map<TopicPartition, Long> beginningOffsets = fetchOffsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, OffsetAndMetadata> committed = fetchCommittedOffsets(partitions);

        long total = 0;
        for (TopicPartition tp : partitions) {
            long endOffset = endOffsets.getOrDefault(tp, 0L);
            long beginningOffset = beginningOffsets.getOrDefault(tp, 0L);

            OffsetAndMetadata committedForPartition = committed.get(tp);
            long baseline = committedForPartition != null ? committedForPartition.offset() : beginningOffset;
            // Retention could have advanced the log's beginning offset past an old commit -
            // clamp so a stale commit never reports a negative or inflated backlog.
            baseline = Math.max(baseline, beginningOffset);

            total += Math.max(0, endOffset - baseline);
        }
        return total;
    }

    private Map<TopicPartition, Long> fetchOffsets(List<TopicPartition> partitions, OffsetSpec spec) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
        partitions.forEach(tp -> request.put(tp, spec));

        ListOffsetsResult result = adminClient.listOffsets(request);
        Map<TopicPartition, Long> offsets = new LinkedHashMap<>();
        for (TopicPartition tp : partitions) {
            offsets.put(tp, result.partitionResult(tp).get(10, TimeUnit.SECONDS).offset());
        }
        return offsets;
    }

    /**
     * If dlqConsumerGroupId isn't configured, or the group has never
     * committed anything (both routine, e.g. DlqReplayService hasn't run
     * yet), returns an empty map rather than throwing - topicUnconsumedDepth
     * correctly falls back to treating everything as unconsumed in that case.
     */
    private Map<TopicPartition, OffsetAndMetadata> fetchCommittedOffsets(List<TopicPartition> partitions) {
        if (dlqConsumerGroupId == null || dlqConsumerGroupId.isBlank()) {
            return Map.of();
        }
        try {
            ListConsumerGroupOffsetsSpec spec = new ListConsumerGroupOffsetsSpec().topicPartitions(partitions);
            return adminClient.listConsumerGroupOffsets(Collections.singletonMap(dlqConsumerGroupId, spec))
                    .partitionsToOffsetAndMetadata(dlqConsumerGroupId)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("No committed offsets found for group {}: {}", dlqConsumerGroupId, e.getMessage());
            return Map.of();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (adminClient != null) {
            adminClient.close();
        }
    }
}
