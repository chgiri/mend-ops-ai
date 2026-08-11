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

CREATE TABLE IF NOT EXISTS approval_audit (
    id                 VARCHAR(255) PRIMARY KEY,
    action_type        VARCHAR(255) NOT NULL,
    description        TEXT         NOT NULL,
    status             VARCHAR(255) NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    resolved_at        TIMESTAMP,
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
