# mend-ops-ai

Agentic self-healing resilience agent for OMS. Standalone Spring Boot service
that watches OMS telemetry and diagnoses/remediates microservice failures,
using a hybrid architecture: a deterministic rule engine handles known
failure patterns for free; an LLM (Gemini, via Spring AI) is escalated to
only when no rule matches.

## Architecture (v1)

```
SystemState snapshot
        |
        v
   RuleEngine.evaluate()  -- first match wins, tracked in coverageRatio()
        |
   matched? ---- yes ----> RemediationAction (Source.RULE_ENGINE), no LLM call
        |
        no
        v
   EscalationService.diagnoseAndAct()  -- Spring AI ChatClient + @Tool calls
        |
        v
   RemediationTools (replayDlqBatch / adjustRetryBudget / pageOncall)
```

- `model/` - `SystemState` (input snapshot) and `RemediationAction` (output).
- `rules/` - `RemediationRule` interface, `RuleEngine` (plain typed policy
  list, not Drools - see project notes on why), and two example rules under
  `rules/impl/` covering known OMS patterns (ProductClient circuit open,
  outbox lag without an open breaker).
- `agent/` - `AgentOrchestrator` (the cascade: rule engine first, LLM
  fallback), `EscalationService` (Spring AI `ChatClient` wiring), and
  `RemediationTools` (the real, scoped actions the LLM can invoke via tool
  calling).
- `controller/` - demo REST endpoints to drive the agent manually before a
  real telemetry poller exists.

All source lives under `com.giri.ai.mendops`.

## Not yet built (intentionally out of v1 scope)

- **Real telemetry ingestion.** `SystemState` is currently supplied over
  HTTP for demo purposes. Next step: a scheduled poller reading Resilience4j
  `CircuitBreakerRegistry`, the outbox table's lag, and Kafka DLQ depth from
  the actual OMS services.
- **Human-approval gate in front of `RemediationTools`.** `EscalationService`
  currently lets the model call any tool directly. Before this touches
  anything beyond local/demo, add an approval step for every `ActionType`
  except `PAGE_ONCALL`.
- **Rule-promotion flow.** The LLM authoring a new `RemediationRule` from a
  resolved unknown incident, queued for human review, then added to
  `RuleEngine`'s rule list. This is the "gets cheaper over time" story - next
  major milestone after the approval gate.
- **Real tool implementations.** `RemediationTools` methods currently log and
  return a string instead of calling Kafka admin / Resilience4j / a paging
  webhook.

## Running locally

```bash
export GEMINI_API_KEY=your-key-here
mvn spring-boot:run
```

Demo endpoints:

```bash
# Known pattern - resolved by the rule engine, no LLM call
curl -X POST http://localhost:8095/api/v1/agent/demo/product-circuit-open

# Unusual combination - no rule matches, escalates to Gemini
curl -X POST http://localhost:8095/api/v1/agent/demo/unknown-pattern

# Coverage metric (rule-engine hit rate vs. LLM escalation rate)
curl http://localhost:8095/api/v1/agent/coverage
```

## Stack

- Java 21
- Spring Boot 4.1.0 (required by Spring AI 2.0.0 - built on Spring
  Framework 7 / Jackson 3)
- Spring AI 2.0.0, `spring-ai-starter-model-google-genai` (Gemini Developer
  API via `GEMINI_API_KEY`; model currently set to `gemini-3.5-flash` in
  `application.properties`)
