package com.giri.ai.mendops.remediation;

import com.giri.ai.mendops.telemetry.OmsTelemetryProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Real implementation behind RemediationTools.replayDlqBatch: reads up to
 * {@code count} messages from a dead-letter topic and republishes them to
 * their original source topic, preserving key/value/headers.
 * <p>
 * Unlike DlqDepthPoller (a long-lived AdminClient reused every poll cycle),
 * this opens a consumer+producer per call and closes them afterward - replay
 * only happens on human approval, not on a tight polling loop, so the
 * connection-setup cost doesn't matter here and closing promptly avoids
 * leaking a consumer group member between approvals.
 * <p>
 * Uses a dedicated consumer group ({@code mendops.remediation.dlq.consumer-group-id})
 * so replay offsets are tracked independently of any real DLQ-reprocessing
 * consumer. Offsets are committed only after each message is successfully
 * republished, so a failed replay can be retried without skipping messages.
 */
@Component
public class DlqReplayService {

    private static final Logger log = LoggerFactory.getLogger(DlqReplayService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(5);

    private final String bootstrapServers;
    private final Map<String, String> sourceTopicOverride;
    private final String consumerGroupId;

    public DlqReplayService(RemediationProperties remediationProperties,
                             OmsTelemetryProperties telemetryProperties) {
        RemediationProperties.Dlq dlqConfig = remediationProperties.dlq();

        // Falls back to the telemetry Kafka config so bootstrap-servers doesn't
        // have to be duplicated across mendops.telemetry.* and
        // mendops.remediation.* when it's the same cluster (the common case).
        String configured = dlqConfig == null ? null : dlqConfig.bootstrapServers();
        this.bootstrapServers = (configured != null && !configured.isBlank())
                ? configured
                : (telemetryProperties.kafka() == null ? null : telemetryProperties.kafka().bootstrapServers());

        this.sourceTopicOverride = (dlqConfig == null || dlqConfig.sourceTopicOverride() == null)
                ? Map.of()
                : dlqConfig.sourceTopicOverride();
        this.consumerGroupId = dlqConfig == null ? "mend-ops-ai-dlq-replay" : dlqConfig.consumerGroupId();
    }

    /**
     * Replays up to {@code count} messages from {@code dlqTopic} onto its
     * source topic. Returns the number actually replayed, which may be less
     * than {@code count} if the DLQ topic doesn't have that many available.
     */
    public int replayBatch(String dlqTopic, int count) {
        if (bootstrapServers == null || bootstrapServers.isBlank()) {
            throw new IllegalStateException(
                    "No Kafka bootstrap-servers configured (mendops.remediation.dlq.bootstrap-servers "
                            + "or mendops.telemetry.kafka.bootstrap-servers) - cannot replay.");
        }

        String sourceTopic = resolveSourceTopic(dlqTopic);
        log.info("Replaying up to {} messages from {} to {}", count, dlqTopic, sourceTopic);

        try (KafkaConsumer<byte[], byte[]> consumer = buildConsumer();
             KafkaProducer<byte[], byte[]> producer = buildProducer()) {

            consumer.subscribe(List.of(dlqTopic));

            int replayed = 0;
            // A couple of empty polls just means we've drained everything currently
            // available on the topic - stop rather than waiting indefinitely for
            // messages that aren't coming.
            int consecutiveEmptyPolls = 0;

            while (replayed < count && consecutiveEmptyPolls < 3) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    consecutiveEmptyPolls++;
                    continue;
                }
                consecutiveEmptyPolls = 0;

                for (ConsumerRecord<byte[], byte[]> record : records) {
                    if (replayed >= count) {
                        break;
                    }
                    republish(producer, sourceTopic, record);
                    replayed++;
                }
                // Commit only what we've actually republished so far, keeping
                // replay-then-commit ordering even if a later record in the
                // same poll batch fails.
                consumer.commitSync();
            }

            log.info("Replayed {} messages from {} to {}", replayed, dlqTopic, sourceTopic);
            return replayed;
        }
    }

    private void republish(KafkaProducer<byte[], byte[]> producer, String sourceTopic,
                            ConsumerRecord<byte[], byte[]> record) {
        ProducerRecord<byte[], byte[]> out =
                new ProducerRecord<>(sourceTopic, null, record.key(), record.value());
        for (Header header : record.headers()) {
            out.headers().add(header);
        }
        try {
            producer.send(out).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to republish DLQ message to " + sourceTopic, e);
        }
    }

    /**
     * Explicit override takes precedence; otherwise assumes Spring Kafka's
     * DeadLetterPublishingRecoverer default naming convention of appending
     * ".DLT" to the source topic name.
     */
    private String resolveSourceTopic(String dlqTopic) {
        String override = sourceTopicOverride.get(dlqTopic);
        if (override != null) {
            return override;
        }
        if (dlqTopic.endsWith(".DLT")) {
            return dlqTopic.substring(0, dlqTopic.length() - ".DLT".length());
        }
        throw new IllegalStateException(
                "Cannot determine source topic for '" + dlqTopic + "' - it doesn't end in .DLT and no "
                        + "mendops.remediation.dlq.source-topic-override entry is configured for it.");
    }

    private KafkaConsumer<byte[], byte[]> buildConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(props);
    }

    private KafkaProducer<byte[], byte[]> buildProducer() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(props);
    }
}
