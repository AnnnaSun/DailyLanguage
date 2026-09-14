package com.dailylanguage.planner.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 Planner enrichment 的 typed configuration；S9D 只注册 resultTtl，
 * 不包含 dispatch、wait 或消费逻辑。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlannerEnrichmentProperties.class)
public class PlannerEnrichmentConfiguration {
}
