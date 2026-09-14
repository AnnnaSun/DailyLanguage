package com.dailylanguage.planner.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Planner enrichment 的 typed deployment configuration；不包含 Credential。
 * resultTtl 只界定 Job result 的保留窗口，与 fixed route 的 executionTimeout（单次 Provider
 * deadline）独立演进。
 */
@ConfigurationProperties("app.planner.enrichment")
public record PlannerEnrichmentProperties(Duration resultTtl) {

    public PlannerEnrichmentProperties {
        Objects.requireNonNull(resultTtl, "resultTtl must not be null");
        if (resultTtl.isZero() || resultTtl.isNegative()) {
            throw new IllegalArgumentException("resultTtl must be positive");
        }
    }
}
