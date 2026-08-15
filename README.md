# mend-ops-ai

Agentic self-healing resilience agent for OMS. Standalone Spring Boot service
that watches OMS telemetry and diagnoses/remediates microservice failures,
using a hybrid architecture: a deterministic rule engine handles known
failure patterns for free; an LLM (Gemini, via Spring AI) is escalated to
only when no rule matches. Rules themselves can grow over time: a pattern
that keeps needing an LLM call gets proposed as a new rule, reviewed by a
human, and - once trusted - starts resolving future occurrences without any
LLM involvement at all.

## Architecture

```
SystemStatePoller (scheduled)
        |
        v
   IncidentTracker.observe() -- tracks recurrence, feeds rule-promotion (see below)
        |
        v
   RuleEngine.evaluate()
        |
        +-- shadow rules (candidates in review) evaluated on every poll,
        |   logged only, never affect the outcome below
        |
        +-- static rules, then live dynamic (promoted) rules - first match wins
        |
   matched? ---- yes ----> RemediationAction (Source.RULE_ENGINE)
        |                        |
        |                  dispatched for REAL (see below) - not just logged
        no
        v
   EscalationService.diagnoseAndAct() -- Spring AI ChatClient + @Tool calls
        |
        v
   RemediationTools
        |
   pageOncall -----------------------------------> executes immediately
        |
   replayDlqBatch / adjustRetryBudget --> ApprovalGate.propose()
        |                                      |
        |                                 PendingApproval (queued, persisted)
        |                                      |
        |                            human calls /approvals/{id}/approve
        |                                      |
        +--------------------------------> RemediationActionExecutor
                                             (real Kafka replay / oms-main
                                              retry-budget call)
```

**Both the rule-engine path and the LLM path now dispatch real actions
through the same gate.** A matched rule - static or a promoted LIVE one - no
longer just logs a diagnosis: `PAGE_ONCALL` pages immediately via
`PagingNotifier`; `ADJUST_RETRY_BUDGET`/`REPLAY_DLQ_BATCH` go through
`ApprovalGate.propose()`, the same human-reviewed queue the LLM path uses.
A promoted rule's human review happens once, at promotion time, over its
condition/action shape - it is not a standing authorization to skip approval
on every future match.

## Rule-promotion flow

```
Same anomalous fact recurs (IncidentTracker, per-fact recurrence tracking)
        |
        v
Recurred >= mendops.rule-candidate.recurrence-threshold times (default 3)
        |
        v
RuleCandidateDraftingService asks the LLM to draft a candidate
  - conditions + target are derived deterministically from the fact itself,
    NOT invented by the LLM - a fact only exists because a known
    AnomalyThresholds value was already crossed
  - the LLM only decides: is this worth a standing rule, which action fits,
    and one reasonable parameter value for that action
        |
        v
RuleCandidate saved as PENDING_REVIEW (GET /api/v1/agent/rule-candidates)
        |
        v
Human: POST .../approve -> APPROVED_SHADOW
  - registered with RuleEngine as a shadow rule: evaluated on every real
    poll, matches are logged ("[SHADOW] would have matched") AND recorded to
    ShadowMatchHistory - GET .../rule-candidates/{id}/shadow-history to
    review hits after the fact instead of only watching logs live
        |
        v
Human: POST .../promote -> LIVE (always a separate, explicit click -
  never auto-promoted after N clean shadow matches)
  - now a real rule: can short-circuit LLM escalation, and its action
    dispatches for real through the same ApprovalGate/PagingNotifier path
    static rules use
```

Candidates are held as **data** (a list of field/operator/value conditions
plus an action type + params), not generated code - an LLM-authored Java
class compiled/loaded at runtime would be arbitrary code execution from
model output. `DataDrivenRule` is the one hand-written interpreter for all
candidates. `RuleCandidateStore` is JPA-backed (`JpaRuleCandidateStore`),
mirroring `ApprovalGate`'s crash-safe pattern - `RuleCandidateReviewService`
reloads every `APPROVED_SHADOW`/`LIVE` row back into `RuleEngine` at startup.

**Which action fits is constrained by the fact's kind, not left fully open.**
Each action's real integration only knows how to target one kind of
identifier - `ADJUST_RETRY_BUDGET` needs a Resilience4j instance name
(`productClient`/`customerClient`), `REPLAY_DLQ_BATCH` needs a Kafka DLQ
topic name. A `CIRCUIT_BREAKER` fact's target is the former; a `DLQ_DEPTH`
fact's target is the latter; an `OUTBOX_LAG` fact's target is neither (it's
an outbox source name, e.g. `shipment-service`) - so `OUTBOX_LAG` facts can
only ever produce `PAGE_ONCALL`. This is enforced twice: the system prompt
tells the LLM the valid action set per fact kind, and
`RuleCandidateDraftingService.resolveActionType()` overrides to `PAGE_ONCALL`
regardless of what the LLM returns if it picks an invalid combination anyway
- prompt compliance is a request, not a guarantee, and a mismatched
combination is exactly what produces a candidate that's guaranteed to fail
once it goes `LIVE` (see `OutboxLagRule`'s Javadoc for the concrete case this
was written to prevent - an earlier version of that rule had exactly this
bug).

## Package layout

- `model/` - `SystemState` (input snapshot) and `RemediationAction` (rule/LLM
  output - now carries a full `actionParams` map, not just a target name, so
  a rule match carries everything needed to actually execute it).
- `rules/` - `RemediationRule` interface, `RuleEngine` (static rules, plus
  dynamic live/shadow rule lists for promoted candidates), `AnomalyThresholds`
  (shared constants - lag/DLQ thresholds - used by rules, `HealthyStateRule`,
  and `IncidentTracker` alike, so their definitions of "anomalous" can't
  silently drift apart), `AnomalousFact` (the single source of truth for the
  anomalous-fact string format), `ShadowMatchHistory`/`JpaShadowMatchHistory`
  (every shadow-rule match, persisted in full - the display endpoint bounds
  what it queries, not what's stored, so old shadow history is never
  destroyed), and `rules/impl/` (`ProductServiceCircuitOpenRule`,
  `OutboxLagRule`, `HealthyStateRule`).
- `incident/` - `IncidentTracker` (per-fact recurrence tracking across poll
  cycles, publishes `IncidentResolvedEvent`), `OpenIncident`.
- `rulecandidate/` - the rule-promotion flow: `RuleCandidate` (data model),
  `RuleCandidateStore`/`JpaRuleCandidateStore`, `RuleCandidateDraftingService`
  (the `@EventListener`), `RuleCandidateReviewService` (status transitions +
  `RuleEngine` registration), `DataDrivenRule`.
- `telemetry/` - real ingestion: `CircuitBreakerPoller` (oms-main's
  `/actuator/circuitbreakers`), `OutboxLagPoller` (JDBC, schema-qualified per
  source), `DlqDepthPoller` (long-lived Kafka `AdminClient` - reports TRUE
  unconsumed depth per topic, end offset minus `DlqReplayService`'s own
  committed offset for that same consumer group, not a raw offset sum -
  DLQ topics have no natural continuous consumer in this system, so
  "replayed" is exactly what "consumed" means here),
  `OutboxDataSourceRegistry`, `OmsTelemetryProperties`, `SystemStatePoller`
  (the `@Scheduled` job tying it all together, and where `IncidentTracker`
  observes each real poll).
- `agent/` - `AgentOrchestrator` (the cascade, now dispatching real actions
  for rule matches too), `EscalationService` (Spring AI wiring),
  `RemediationTools` (LLM-callable actions), `ApprovalGate`/`PendingApproval`
  (the approval queue - persisted via JPA, crash-safe resumable),
  `RemediationActionExecutor`/`Impl` (dispatches gated actions from data).
- `remediation/` - the real integrations: `DlqReplayService` (dedicated
  Kafka consumer group, offset-committed only after successful republish),
  `RetryBudgetAdminClient` (calls oms-main's `/actuator/retrybudget`),
  `PagingNotifier` (webhook), `OmsAuthClient`/`OmsAuthProperties`
  (authenticates as mend-ops-ai's SERVICE-role account on oms-main),
  `RemediationProperties`.
- `agent/audit/` - `ApprovalAuditEntity`/`Repository` (JPA persistence for
  the approval queue).
- `config/` - `MendOpsDataSourceConfig`/`MendOpsPersistenceProperties`
  (mend-ops-ai's own dedicated Postgres, for the approval audit trail).
- `controller/` - `AgentController` (demo endpoints + coverage metric),
  `ApprovalController`, `RuleCandidateController`.

## Real tool implementations

`RemediationTools`' gated actions call real integrations, not placeholders:

- **`DlqReplayService`** - a dedicated Kafka consumer group (never the same
  group a real reprocessing consumer would use), committing offsets only
  *after* successful republish so a failed replay is retryable without
  skipping messages, preserving original message headers, and exiting after
  3 consecutive empty polls rather than blocking indefinitely. Configured via
  `mendops.remediation.dlq.consumer-group-id` and
  `mendops.remediation.dlq.source-topic-override` (per-topic override for
  DLQ topics that don't follow the `<source>.DLT` naming convention).
- **`RetryBudgetAdminClient`** - calls oms-main's `/actuator/retrybudget`
  (see "Rule-promotion flow" above and oms-main's own `RetryBudgetEndpoint`),
  authenticating via `OmsAuthClient` as mend-ops-ai's SERVICE-role account.
  Base URL per Resilience4j instance name under
  `mendops.remediation.retry-budget.admin-base-url.<instanceName>`.
- **`PagingNotifier`** - a webhook call, configured via
  `mendops.remediation.paging.webhook-url`.

## Crash-safe resumable approvals

`ApprovalGate`/`PendingApproval` are backed by JPA
(`agent/audit/ApprovalAuditEntity`/`Repository`) against mend-ops-ai's own
dedicated Postgres container (`mend-ops-postgres` in `docker-compose.yml` -
fully separate from oms-main's own Postgres, not a sibling database on it;
schema created via `db/init.sql`, `ddl-auto=validate` so a schema drift
fails startup loudly instead of Hibernate silently mutating it). Configured
via `mendops.persistence.jdbc-url`/`username`/`password`
(`MENDOPS_PERSISTENCE_JDBCURL`/`MENDOPS_DB_USER`/`MENDOPS_DB_PASSWORD`).

On startup, `ApprovalGate` reloads every persisted approval and re-registers
its real execution `Callable` by reconstructing it from the persisted
`actionType` + params (this is exactly why `PendingApproval` stores actions
as data, not a captured closure - a `Callable` can't survive a restart, data
can). `PENDING` and `FAILED` approvals are resumable after a restart -
calling `/approve` on either re-attempts the real action. `APPROVED` and
`REJECTED` are terminal.

Audit writes are best-effort and non-fatal to the actual remediation - a DB
hiccup during a write shouldn't make a successful action report as failed.
The honest tradeoff: an approval *proposed* while the database is down won't
survive a restart, since it was never durably recorded in the first place.

## Not yet built / open items

- **No revert/expiry for a "temporarily" widened retry budget.** Left as-is
  forever once approved - a real gap if this ever runs unattended.
- **Paging-bias question, unresolved.** `EscalationService`'s system prompt
  tells the LLM to prefer paging when a situation is ambiguous or could
  involve data loss - real testing suggested it may default to paging more
  often than actually exercising `adjustRetryBudget`/`replayDlqBatch`. Never
  conclusively confirmed either way; worth deliberately testing before
  assuming the gated-action paths get meaningfully exercised in practice.
- **Everything built in the rule-promotion flow and the execution-gap fix is
  untested end-to-end** - each piece was verified in isolation or by code
  review, but the full loop (incident recurs -> candidate drafted -> shadow
  -> promoted -> a live rule match actually pages/proposes for real) has
  never been run start to finish.

## Running locally

```bash
export GEMINI_API_KEY=your-key-here
mvn spring-boot:run
```

Requires oms-main running and reachable (actuator on its management port,
Postgres/Kafka up), mend-ops-ai's own dedicated Postgres for the approval
audit trail (`db/init.sql`, `ddl-auto=validate`), and a seeded SERVICE-role
account on oms-main (`ServiceAccountSeeder`, on oms-main's side) for
`OmsAuthClient` to authenticate with.

Demo endpoints remain useful for forcing a specific scenario on demand:

```bash
# Known pattern - resolved by the rule engine, no LLM call
curl -X POST http://localhost:8099/api/v1/agent/demo/product-circuit-open

# HALF_OPEN breaker plus a real DLQ backlog (live, unresolved signal) - typically escalates to pageOncall
curl -X POST http://localhost:8099/api/v1/agent/demo/unknown-pattern

# Breakers CLOSED/lag fine but a DLQ backlog remains - typically escalates to replayDlqBatch
curl -X POST http://localhost:8099/api/v1/agent/demo/dlq-backlog-recovered

# HALF_OPEN breaker but no other symptoms (low lag, low DLQ) - typically escalates to adjustRetryBudget
curl -X POST http://localhost:8099/api/v1/agent/demo/transient-backpressure

# Coverage metric (rule-engine hit rate vs. LLM escalation rate)
curl http://localhost:8099/api/v1/agent/coverage

# Pending human approvals (gated remediation actions)
curl http://localhost:8099/api/v1/agent/approvals?pendingOnly=true
curl -X POST http://localhost:8099/api/v1/agent/approvals/<id>/approve
curl -X POST http://localhost:8099/api/v1/agent/approvals/<id>/reject

# Rule candidates awaiting review
curl http://localhost:8099/api/v1/agent/rule-candidates
curl -X POST http://localhost:8099/api/v1/agent/rule-candidates/<id>/approve  # -> shadow
curl -X POST http://localhost:8099/api/v1/agent/rule-candidates/<id>/promote # -> live
curl -X POST http://localhost:8099/api/v1/agent/rule-candidates/<id>/reject
```

Which action the LLM picks for the two "no rule matches" demos is a model decision, not
guaranteed - but breaker state plus whether there's a backlog is the strongest signal for it:
HALF_OPEN with a real backlog (high lag, high DLQ depth) reads as a live, unresolved incident
(favors `pageOncall`); all-CLOSED breakers with a lingering DLQ backlog reads as "already
recovered, just needs cleanup" (favors `replayDlqBatch`); a HALF_OPEN breaker with no other
symptoms reads as "mild, already recovering on its own, just needs a wider retry budget"
(favors `adjustRetryBudget`). This is also why `HealthyStateRule` checks DLQ depth now, not
just breaker state and lag - without that, an all-CLOSED-breakers scenario with a growing
backlog was being wrongly classified healthy and never reached the LLM at all.

## Stack

- Java 21
- Spring Boot 4.1.0 (required by Spring AI 2.0.0 - built on Spring
  Framework 7 / Jackson 3; note Jackson 3's packages moved from
  `com.fasterxml.jackson.*` to `tools.jackson.*`, annotations excepted, and
  `org.springframework.boot.autoconfigure.*` classes moved to per-technology
  packages like `org.springframework.boot.jdbc.autoconfigure.*`)
- Spring AI 2.0.0, `spring-ai-starter-model-google-genai` (Gemini Developer
  API via `GEMINI_API_KEY`; model currently set to `gemini-3.5-flash` in
  `application.properties`)
- Spring Data JPA + a dedicated Postgres database (mend-ops-ai's own approval
  audit trail - separate from the read-only outbox-polling JDBC access into
  oms-main/product-service/customer-service's own databases)
