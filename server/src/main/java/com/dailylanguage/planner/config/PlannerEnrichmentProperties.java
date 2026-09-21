package com.dailylanguage.planner.config;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Planner enrichment 的 typed deployment configuration；不包含 Credential。
 * resultTtl 只界定 Job result 的保留窗口，与 fixed route 的 executionTimeout（单次 Provider
 * deadline）独立演进。interactiveWait / pollInterval 只界定 S9E1 只读等待循环的交互预算，
 * 与 Gateway execution timeout 语义分离：budget 到期不代表 Provider 调用失败。
 */
@ConfigurationProperties("app.planner.enrichment")
public record PlannerEnrichmentProperties(
        Duration resultTtl,
        Duration interactiveWait,
        Duration pollInterval) {

    public PlannerEnrichmentProperties {
        requirePositive(resultTtl, "resultTtl");
        requirePositive(interactiveWait, "interactiveWait");
        requirePositive(pollInterval, "pollInterval");
        if (pollInterval.compareTo(interactiveWait) > 0) {
            throw new IllegalArgumentException("pollInterval must not exceed interactiveWait");
        }
    }

    private static void requirePositive(Duration duration, String fieldName) {
        Objects.requireNonNull(duration, fieldName + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
