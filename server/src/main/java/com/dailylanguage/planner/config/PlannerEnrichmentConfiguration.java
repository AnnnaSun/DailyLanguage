package com.dailylanguage.planner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 Planner enrichment 的 typed configuration：resultTtl（S9D）与 interactiveWait /
 * pollInterval（S9E1 只读等待循环）；不包含 dispatch、wait 或消费逻辑本身。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlannerEnrichmentProperties.class)
public class PlannerEnrichmentConfiguration {
}
