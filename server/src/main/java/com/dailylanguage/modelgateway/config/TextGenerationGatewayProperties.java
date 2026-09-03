package com.dailylanguage.modelgateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * Text Generation runtime composition 的 typed deployment configuration，不包含 Credential。
 */
@ConfigurationProperties("app.model-gateway.text-generation")
public record TextGenerationGatewayProperties(
        OpenAiCompatibleProviderSettings openAiCompatibleProvider,
        Map<ModelPurpose, RouteSettings> routes,
        ExecutorSettings executor) {

    public TextGenerationGatewayProperties {
        Objects.requireNonNull(openAiCompatibleProvider, "openAiCompatibleProvider must not be null");
        Objects.requireNonNull(routes, "routes must not be null");
        Objects.requireNonNull(executor, "executor must not be null");
        if (routes.isEmpty()) {
            throw new IllegalArgumentException("routes must not be empty");
        }
        routes = Map.copyOf(routes);
    }

    public record OpenAiCompatibleProviderSettings(
            String providerId,
            URI chatCompletionsEndpoint) {

        public OpenAiCompatibleProviderSettings {
            new ProviderId(providerId);
            Objects.requireNonNull(chatCompletionsEndpoint, "chatCompletionsEndpoint must not be null");
        }
    }

    public record RouteSettings(
            String modelId,
            Duration executionTimeout) {

        public RouteSettings {
            new ModelId(modelId);
            Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
            if (executionTimeout.isZero() || executionTimeout.isNegative()) {
                throw new IllegalArgumentException("executionTimeout must be positive");
            }
        }
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
