# mend-ops-ai

Agentic self-healing resilience agent for OMS. Standalone Spring Boot service
that watches OMS telemetry and diagnoses/remediates microservice failures,
using a hybrid architecture: a deterministic rule engine handles known
failure patterns for free; an LLM (Gemini, via Spring AI) is escalated to
only when no rule matches.

## Architecture (v1)

```
SystemStatePoller (scheduled)
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
   RemediationTools
        |
   pageOncall -----------------------------------> executes immediately
        |
   replayDlqBatch / adjustRetryBudget --> ApprovalGate.propose()
        |                                      |
        |                                 PendingApproval (queued)
        |                                      |
        |                            human calls /approvals/{id}/approve
        |                                      |
        +--------------------------------> real action executes
```

- `model/` - `SystemState` (input snapshot) and `RemediationAction` (output).
- `rules/` - `RemediationRule` interface, `RuleEngine` (plain typed policy
  list, not Drools - see project notes on why), and rules under `rules/impl/`:
  `ProductServiceCircuitOpenRule`, `OutboxLagRule` (known anomaly patterns),
  and `HealthyStateRule` (catches the steady/nothing-wrong state so
  continuous polling doesn't escalate to the LLM every cycle).
- `telemetry/` - the real ingestion layer, replacing the original demo-only
  `SystemState` input: `CircuitBreakerPoller` (oms-main's
  `/actuator/circuitbreakers`), `OutboxLagPoller` (JDBC against each
  service's own outbox table, schema-qualified per source where needed),
  `DlqDepthPoller` (Kafka `AdminClient`, long-lived, not recreated per poll),
  `OutboxDataSourceRegistry` (one small connection pool per outbox source),
  `OmsTelemetryProperties` (typed config), and `SystemStatePoller` (the
  `@Scheduled` job tying the three pollers into `AgentOrchestrator`).
- `agent/` - `AgentOrchestrator` (the cascade: rule engine first, LLM
  fallback), `EscalationService` (Spring AI `ChatClient` wiring),
  `RemediationTools` (the actions the LLM can invoke via tool calling), and
  the approval gate: `ApprovalGate` (in-memory pending-approval store) and
  `PendingApproval` (a proposed risky action + the real execution captured
  as a `Callable`, only run on approval).
- `controller/` - `AgentController` (demo endpoints + coverage metric) and
  `ApprovalController` (list/approve/reject pending approvals).

All source lives under `com.giri.ai.mendops`.

## How the approval gate works

`replayDlqBatch` and `adjustRetryBudget` no longer execute when the LLM
calls them - they call `ApprovalGate.propose(...)`, which stores a
`PendingApproval` and returns only an id/acknowledgement to the model. The
real Kafka/Resilience4j call is captured as a `Callable` at proposal time
and only runs when a human calls the approve endpoint. `pageOncall` is the
one exception - it's a notification, not a destructive action, so it still
executes immediately.

```bash
# See what's awaiting approval
curl http://localhost:8095/api/v1/agent/approvals?pendingOnly=true

# Approve one (this is when the real action actually runs)
curl -X POST http://localhost:8095/api/v1/agent/approvals/<id>/approve

# Reject one (no action taken, marked resolved)
curl -X POST http://localhost:8095/api/v1/agent/approvals/<id>/reject
```

v1 stores approvals in memory only (lost on restart) - fine for local/demo
use. If this needs to survive a restart or be visible across instances,
that's the point to add JPA persistence.

## Real tool implementations

`RemediationTools`' captured `Callable`s call real integrations, in
`remediation/` (`RemediationProperties`, `DlqReplayService`,
`RetryBudgetAdminClient`, `PagingNotifier`):

- **`replayDlqBatch`** - a dedicated Kafka consumer group
  (`mendops.remediation.dlq.consumer-group-id`) reads up to `count` messages
  from the DLQ topic and republishes them (key/value/headers preserved) to
  the source topic. Source topic is derived by stripping a `.DLT` suffix
  (Spring Kafka's default dead-letter naming convention) unless overridden
  per-topic via `mendops.remediation.dlq.source-topic-override`. Falls back
  to `mendops.telemetry.kafka.bootstrap-servers` if
  `mendops.remediation.dlq.bootstrap-servers` is unset.
- **`adjustRetryBudget`** - `productClient`/`customerClient` are Resilience4j
  instance names for oms-main's own outbound clients (see e.g.
  `CustomerClientImpl`) - product-service/customer-service don't implement
  Resilience4j themselves. So this calls an admin endpoint on **oms-main**
  (`POST {admin-base-url}/internal/resilience/retry-budget`, body
  `{"clientName": "...", "maxAttempts": N}`), same host as
  `mendops.telemetry.circuit-breakers.actuator-base-url`. Configured per
  Resilience4j instance name (not per downstream service) under
  `mendops.remediation.retry-budget.admin-base-url.<instanceName>` - in
  practice both `productClient` and `customerClient` point at the same
  oms-main host. If oms-main doesn't expose that endpoint yet, the call
  fails loudly rather than silently no-oping.
- **`pageOncall`** - POSTs `{"text": "..."}` to a Slack-incoming-webhook-style
  URL (`mendops.remediation.paging.webhook-url`). Since paging executes
  immediately and isn't approval-gated, a missing/failing webhook logs at
  WARN/ERROR instead of throwing, so it never blocks the one action path
  that's supposed to always work.

## Not yet built (intentionally out of v1 scope)

- **Rule-promotion flow.** The LLM authoring a new `RemediationRule` from a
  resolved unknown incident, queued for human review, then added to
  `RuleEngine`'s rule list. This is the "gets cheaper over time" story - the
  next major milestone now that telemetry + the approval gate are both done.
- **Persistent approval/audit history.** Currently in-memory only.

## Running locally

```bash
export GEMINI_API_KEY=your-key-here
mvn spring-boot:run
```

Real telemetry polling starts automatically on a schedule
(`mendops.telemetry.poll-interval-ms`, default 30s) once
`mendops.telemetry.*` properties are configured against your running OMS
stack. Demo endpoints remain useful for forcing a specific scenario on
demand without waiting for a real failure:

```bash
# Known pattern - resolved by the rule engine, no LLM call
curl -X POST http://localhost:8095/api/v1/agent/demo/product-circuit-open

# Unusual combination, HALF_OPEN breaker (still-live signal) - typically escalates to pageOncall
curl -X POST http://localhost:8095/api/v1/agent/demo/unknown-pattern

# Breakers CLOSED/lag fine but a DLQ backlog remains - typically escalates to replayDlqBatch
curl -X POST http://localhost:8095/api/v1/agent/demo/dlq-backlog-recovered

# Coverage metric (rule-engine hit rate vs. LLM escalation rate)
curl http://localhost:8095/api/v1/agent/coverage
```

Which action the LLM picks is a model decision, not guaranteed - but breaker state is
the strongest signal for it: HALF_OPEN/OPEN reads as a live, unresolved incident (favors
pageOncall), while all-CLOSED breakers with a lingering DLQ backlog reads as "already
recovered, just needs cleanup" (favors replayDlqBatch). `HealthyStateRule` also checks DLQ
depth now, not just breaker state and lag - without that, an all-CLOSED-breakers scenario
with a growing backlog was being wrongly classified healthy and never reached the LLM at all.

## Stack

- Java 21
- Spring Boot 4.1.0 (required by Spring AI 2.0.0 - built on Spring
  Framework 7 / Jackson 3; note Jackson 3's packages moved from
  `com.fasterxml.jackson.*` to `tools.jackson.*`, annotations excepted)
- Spring AI 2.0.0, `spring-ai-starter-model-google-genai` (Gemini Developer
  API via `GEMINI_API_KEY`; model currently set to `gemini-3.5-flash` in
  `application.properties`)
