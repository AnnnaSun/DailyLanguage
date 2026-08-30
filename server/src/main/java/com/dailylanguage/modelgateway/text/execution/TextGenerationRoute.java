package com.dailylanguage.modelgateway.text.execution;

import java.time.Duration;
import java.util.Objects;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * 已完成 runtime wiring 的 Text Generation route；配置值与执行 Adapter 通过 Composition 绑定。
 */
public record TextGenerationRoute(
        ProviderId providerId,
        ModelId modelId,
        TextGenerationProviderAdapter adapter,
        Duration executionTimeout) {

    public TextGenerationRoute {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(adapter, "adapter must not be null");
        Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
        if (executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("executionTimeout must be positive");
        }
    }
}
