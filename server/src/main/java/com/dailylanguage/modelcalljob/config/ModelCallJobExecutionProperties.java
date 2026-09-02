package com.dailylanguage.modelcalljob.config;

import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Model Call Job 执行基础设施的 typed deployment configuration，不包含 Credential。
 */
@ConfigurationProperties("app.model-call-job.execution")
public record ModelCallJobExecutionProperties(ExecutorSettings executor) {

    public ModelCallJobExecutionProperties {
        Objects.requireNonNull(executor, "executor must not be null");
    }

    public record ExecutorSettings(
            int workers,
            int queueCapacity) {

        public ExecutorSettings {
            if (workers < 1) {
                throw new IllegalArgumentException("executor workers must be positive");
            }
            if (queueCapacity < 1) {
                throw new IllegalArgumentException("executor queueCapacity must be positive");
            }
        }
    }
}
