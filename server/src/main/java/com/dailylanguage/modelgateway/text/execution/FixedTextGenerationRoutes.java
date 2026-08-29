package com.dailylanguage.modelgateway.text.execution;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;

/**
 * Text Generation 的不可变 fixed route mapping；不进行动态选择、健康检查或 fallback。
 */
public final class FixedTextGenerationRoutes {

    private final Map<ModelRouteKey, TextGenerationRoute> routes;

    public FixedTextGenerationRoutes(Map<ModelRouteKey, TextGenerationRoute> routes) {
        Objects.requireNonNull(routes, "routes must not be null");
        routes.forEach(FixedTextGenerationRoutes::validateRoute);
        this.routes = Map.copyOf(routes);
    }

    public Optional<TextGenerationRoute> findRoute(ModelPurpose purpose) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        return Optional.ofNullable(routes.get(new ModelRouteKey(purpose, ModelOperation.TEXT_GENERATION)));
    }

    private static void validateRoute(ModelRouteKey key, TextGenerationRoute route) {
        Objects.requireNonNull(key, "route key must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (key.operation() != ModelOperation.TEXT_GENERATION) {
            throw new IllegalArgumentException("fixed text routes only accept TEXT_GENERATION operation");
        }
    }
}
