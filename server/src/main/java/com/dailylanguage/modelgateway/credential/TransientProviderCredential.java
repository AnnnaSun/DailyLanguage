package com.dailylanguage.modelgateway.credential;

import java.util.Objects;

import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * 单次 Model execution 使用的 Provider Credential，只能在当前内存调用链中传播。
 */
public final class TransientProviderCredential {

    private final ProviderId providerId;
    private final String secret;

    public TransientProviderCredential(ProviderId providerId, String secret) {
        this.providerId = Objects.requireNonNull(providerId, "providerId must not be null");
        this.secret = Objects.requireNonNull(secret, "secret must not be null");
        if (secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
    }

    public ProviderId providerId() {
        return providerId;
    }

    /**
     * 只允许 operation-specific Provider Adapter 在构造外部请求时读取，不得写入日志、Trace 或持久化状态。
     */
    public String secret() {
        return secret;
    }

    @Override
    public String toString() {
        return "TransientProviderCredential[providerId=" + providerId + ", secret=REDACTED]";
    }
}
