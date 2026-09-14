package com.dailylanguage.planner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PlannerEnrichmentConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PlannerEnrichmentConfiguration.class);

    @Test
    void bindsDefaultFiveMinuteResultTtlFromClasspathConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PlannerEnrichmentProperties.class);

            // application.yml 的 app.planner.enrichment.result-ttl 默认 5m，
            // 与 fixed route 的 executionTimeout（30s）独立。
            assertThat(context.getBean(PlannerEnrichmentProperties.class).resultTtl())
                    .isEqualTo(Duration.ofMinutes(5));
        });
    }

    @Test
    void overridesResultTtlFromProperty() {
        contextRunner
                .withPropertyValues("app.planner.enrichment.result-ttl=42s")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context.getBean(PlannerEnrichmentProperties.class).resultTtl())
                            .isEqualTo(Duration.ofSeconds(42));
                });
    }

    @Test
    void rejectsNonPositiveResultTtlAtBinding() {
        contextRunner
                .withPropertyValues("app.planner.enrichment.result-ttl=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.planner.enrichment.result-ttl=-5m")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsNonPositiveResultTtlOnDirectConstruction() {
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resultTtl must not be null");
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultTtl must be positive");
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultTtl must be positive");
    }
}
