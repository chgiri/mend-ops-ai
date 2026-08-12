package com.giri.ai.mendops.rules.impl;

import com.giri.ai.mendops.model.RemediationAction;
import com.giri.ai.mendops.model.SystemState;
import com.giri.ai.mendops.rules.RemediationRule;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Known pattern: ProductClient's circuit breaker has tripped open.
 * This is a well-understood failure mode from OMS's Stage 4 extraction -
 * order creation calls product-service synchronously via ProductClient,
 * so an open breaker there directly blocks order creation.
 */
@Component
public class ProductServiceCircuitOpenRule implements RemediationRule {

    private static final String CIRCUIT_NAME = "productClient";

    @Override
    public String id() {
        return "product-client-circuit-open";
    }

    @Override
    public String description() {
        return "ProductClient circuit breaker is OPEN - product-service calls from order "
                + "creation are failing fast.";
    }

    @Override
    public boolean matches(SystemState state) {
        return state.circuitBreakers().get(CIRCUIT_NAME) == SystemState.CircuitBreakerState.OPEN;
    }

    @Override
    public RemediationAction actionFor(SystemState state) {
        String diagnosis = "ProductClient circuit breaker is open; product-service is likely degraded or "
                + "unreachable, blocking order creation.";
        return new RemediationAction(
                diagnosis,
                RemediationAction.ActionType.PAGE_ONCALL,
                "product-service",
                RemediationAction.Source.RULE_ENGINE,
                Map.of("summary", diagnosis)
        );
    }
}
