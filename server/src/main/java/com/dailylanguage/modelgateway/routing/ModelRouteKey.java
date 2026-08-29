package com.dailylanguage.modelgateway.routing;

import java.util.Objects;

/**
 * 使用业务 Purpose 与技术 Operation 共同定位一条 Model route。
 */
public record ModelRouteKey(ModelPurpose purpose, ModelOperation operation) {

    public ModelRouteKey {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
