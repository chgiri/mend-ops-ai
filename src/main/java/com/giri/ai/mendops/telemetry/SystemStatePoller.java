package com.giri.ai.mendops.telemetry;

import com.giri.ai.mendops.agent.AgentOrchestrator;
import com.giri.ai.mendops.model.SystemState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Replaces the demo REST endpoints as the real entry point into the agent:
 * on a fixed schedule, pulls a fresh SystemState from all three telemetry
 * sources and hands it to AgentOrchestrator, exactly as the demo controller
 * did manually.
 * <p>
 * The demo endpoints in AgentController are left in place deliberately -
 * useful for forcing a specific scenario (e.g. the unknown-pattern demo)
 * without waiting for a real failure to occur.
 */
@Component
public class SystemStatePoller {

    private static final Logger log = LoggerFactory.getLogger(SystemStatePoller.class);

    private final CircuitBreakerPoller circuitBreakerPoller;
    private final OutboxLagPoller outboxLagPoller;
    private final DlqDepthPoller dlqDepthPoller;
    private final AgentOrchestrator orchestrator;

    public SystemStatePoller(CircuitBreakerPoller circuitBreakerPoller,
                              OutboxLagPoller outboxLagPoller,
                              DlqDepthPoller dlqDepthPoller,
                              AgentOrchestrator orchestrator) {
        this.circuitBreakerPoller = circuitBreakerPoller;
        this.outboxLagPoller = outboxLagPoller;
        this.dlqDepthPoller = dlqDepthPoller;
        this.orchestrator = orchestrator;
    }

    @Scheduled(fixedDelayString = "${mendops.telemetry.poll-interval-ms:30000}")
    public void pollAndEvaluate() {
        SystemState state = new SystemState(
                Instant.now(),
                circuitBreakerPoller.poll(),
                outboxLagPoller.poll(),
                dlqDepthPoller.poll()
        );

        log.debug("Polled SystemState: {}", state);

        String result = orchestrator.handle(state);
        log.info("Agent evaluation result: {}", result);
    }
}
