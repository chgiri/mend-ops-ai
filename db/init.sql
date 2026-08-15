-- Schema for mend-ops-ai's approval/audit trail (ApprovalAuditEntity).
--
-- Runs automatically on mend-ops-postgres's first boot (mounted into
-- /docker-entrypoint-initdb.d/ in docker-compose.yml - the official postgres
-- image only executes files there when the data directory is empty, i.e.
-- once, not on every restart).
--
-- Column names/types here match ApprovalAuditEntity's JPA mapping exactly -
-- Spring Boot's default naming strategy converts camelCase fields to
-- snake_case columns (actionType -> action_type, etc.), and params' three
-- columns match its explicit @Column/@MapKeyColumn/@JoinColumn names.
-- spring.jpa.hibernate.ddl-auto=validate (see application.properties) means
-- Hibernate checks this schema matches the entities at startup rather than
-- silently creating/altering it - if you add a field to ApprovalAuditEntity,
-- add the matching column here too, or startup will fail validation.

-- created_at/resolved_at are TIMESTAMPTZ, not plain TIMESTAMP - Hibernate 6+
-- defaults to mapping java.time.Instant (see ApprovalAuditEntity) to
-- TIMESTAMP WITH TIME ZONE, and ddl-auto=validate fails startup outright on
-- a column-type mismatch rather than tolerating it.

CREATE TABLE IF NOT EXISTS approval_audit (
    id                 VARCHAR(255) PRIMARY KEY,
    action_type        VARCHAR(255) NOT NULL,
    description        TEXT         NOT NULL,
    status             VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    resolved_at        TIMESTAMPTZ,
    execution_result   TEXT,
    failure_reason     TEXT
);

CREATE TABLE IF NOT EXISTS approval_audit_params (
    approval_id VARCHAR(255) NOT NULL REFERENCES approval_audit (id) ON DELETE CASCADE,
    param_key   VARCHAR(255) NOT NULL,
    param_value VARCHAR(255),
    PRIMARY KEY (approval_id, param_key)
);

CREATE INDEX IF NOT EXISTS idx_approval_audit_status ON approval_audit (status);
CREATE INDEX IF NOT EXISTS idx_approval_audit_created_at ON approval_audit (created_at);

-- Schema for the rule-promotion flow's persistence (RuleCandidateEntity) -
-- same reasoning as approval_audit above: column names/types match the JPA
-- mapping exactly, validated (not created/altered) at startup.

CREATE TABLE IF NOT EXISTS rule_candidate (
    id                            VARCHAR(255) PRIMARY KEY,
    source_fact                   VARCHAR(255) NOT NULL,
    occurrence_count_at_drafting  INTEGER      NOT NULL,
    diagnosis                     TEXT,
    action_type                   VARCHAR(255) NOT NULL,
    status                        VARCHAR(255) NOT NULL,
    created_at                    TIMESTAMPTZ  NOT NULL,
    resolved_at                   TIMESTAMPTZ
);

-- One row per RuleCandidate.Condition, in order (condition_order backs
-- @OrderColumn on RuleCandidateEntity.conditions - a candidate's conditions
-- are evaluated as a flat AND, so order doesn't affect matching, but is
-- preserved for stable, predictable display).
CREATE TABLE IF NOT EXISTS rule_candidate_condition (
    rule_candidate_id VARCHAR(255) NOT NULL REFERENCES rule_candidate (id) ON DELETE CASCADE,
    condition_order   INTEGER      NOT NULL,
    field             VARCHAR(255),
    operator          VARCHAR(255),
    value             VARCHAR(255),
    PRIMARY KEY (rule_candidate_id, condition_order)
);

CREATE TABLE IF NOT EXISTS rule_candidate_action_param (
    rule_candidate_id VARCHAR(255) NOT NULL REFERENCES rule_candidate (id) ON DELETE CASCADE,
    param_key         VARCHAR(255) NOT NULL,
    param_value       VARCHAR(255),
    PRIMARY KEY (rule_candidate_id, param_key)
);

CREATE INDEX IF NOT EXISTS idx_rule_candidate_status ON rule_candidate (status);
CREATE INDEX IF NOT EXISTS idx_rule_candidate_source_fact ON rule_candidate (source_fact);

-- Schema for ShadowMatchEntity - every shadow-rule match, kept in full (no
-- eviction here; JpaShadowMatchHistory bounds what it QUERIES for display,
-- not what's stored). No FK to rule_candidate on purpose - shadow-match
-- history should survive independently of the candidate row's own
-- lifecycle, the same way approval_audit doesn't FK to anything external
-- either.
CREATE TABLE IF NOT EXISTS shadow_match (
    id             VARCHAR(255) PRIMARY KEY,
    rule_id        VARCHAR(255) NOT NULL,
    matched_at     TIMESTAMPTZ  NOT NULL,
    diagnosis      TEXT,
    action_summary VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_shadow_match_rule_id_matched_at ON shadow_match (rule_id, matched_at DESC);
