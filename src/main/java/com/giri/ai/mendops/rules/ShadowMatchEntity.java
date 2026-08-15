package com.giri.ai.mendops.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Durable row for one ShadowMatchRecord - written by JpaShadowMatchHistory.
 * Every match is kept (no eviction here, unlike the original in-memory
 * version); JpaShadowMatchHistory.forRule() bounds what it QUERIES for
 * display, not what's stored, so historical shadow-match data isn't
 * silently destroyed the way it would be by an in-memory eviction policy.
 */
@Entity
@Table(name = "shadow_match")
public class ShadowMatchEntity {

    @Id
    private String id;

    @Column(nullable = false)
    private String ruleId;

    @Column(nullable = false)
    private Instant matchedAt;

    @Lob
    private String diagnosis;

    @Column
    private String actionSummary;

    /** JPA requires a no-arg constructor - not for application use. */
    protected ShadowMatchEntity() {
    }

    public ShadowMatchEntity(String id, String ruleId, Instant matchedAt, String diagnosis, String actionSummary) {
        this.id = id;
        this.ruleId = ruleId;
        this.matchedAt = matchedAt;
        this.diagnosis = diagnosis;
        this.actionSummary = actionSummary;
    }

    public String getId() {
        return id;
    }

    public String getRuleId() {
        return ruleId;
    }

    public Instant getMatchedAt() {
        return matchedAt;
    }

    public String getDiagnosis() {
        return diagnosis;
    }

    public String getActionSummary() {
        return actionSummary;
    }
}
