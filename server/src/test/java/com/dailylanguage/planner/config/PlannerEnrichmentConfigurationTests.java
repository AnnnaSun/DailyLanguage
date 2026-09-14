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
    void bindsDefaultInteractiveWaitAndPollIntervalFromClasspathConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PlannerEnrichmentProperties.class);

            PlannerEnrichmentProperties properties = context.getBean(PlannerEnrichmentProperties.class);
            // S9 dossier 批准的等待循环默认：interactive 2s、poll 50ms；
            // 与 Gateway execution timeout 语义分离。
            assertThat(properties.interactiveWait()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(50));
        });
    }

    @Test
    void overridesInteractiveWaitAndPollIntervalFromProperty() {
        contextRunner
                .withPropertyValues(
                        "app.planner.enrichment.interactive-wait=25s",
                        "app.planner.enrichment.poll-interval=5s")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    PlannerEnrichmentProperties properties =
                            context.getBean(PlannerEnrichmentProperties.class);
                    assertThat(properties.interactiveWait()).isEqualTo(Duration.ofSeconds(25));
                    assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    void rejectsPollIntervalExceedingInteractiveWait() {
        contextRunner
                .withPropertyValues(
                        "app.planner.enrichment.interactive-wait=1s",
                        "app.planner.enrichment.poll-interval=2s")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pollInterval must not exceed interactiveWait");
    }

    @Test
    void rejectsNonPositiveInteractiveWaitOrPollIntervalAtBinding() {
        contextRunner
                .withPropertyValues("app.planner.enrichment.interactive-wait=0s")
                .run(context -> assertThat(context).hasFailed());
        contextRunner
                .withPropertyValues("app.planner.enrichment.poll-interval=-1s")
                .run(context -> assertThat(context).hasFailed());
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                Duration.ofMinutes(5), Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("interactiveWait must be positive");
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("pollInterval must be positive");
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                Duration.ofMinutes(5), null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("interactiveWait must not be null");
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
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                null, Duration.ofSeconds(10), Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("resultTtl must not be null");
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultTtl must be positive");
        assertThatThrownBy(() -> new PlannerEnrichmentProperties(
                Duration.ofSeconds(-1), Duration.ofSeconds(10), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultTtl must be positive");
    }
}
