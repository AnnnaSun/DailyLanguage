package com.dailylanguage.modelgateway.text.execution;

import java.time.Duration;
import java.util.UUID;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

/**
 * 单个 Provider 执行 Text Generation 的 operation-specific SPI，不暴露 Provider SDK type。
 * {@code traceId} 只用于关联当前调用的安全诊断，不授权 Adapter 持久化 request、response 或 Credential。
 */
public interface TextGenerationProviderAdapter {

    ModelResult<TextGenerationResponse> generateText(
            UUID traceId,
            ProviderId providerId,
            ModelId modelId,
            TextGenerationRequest request,
            TransientProviderCredential credential,
            Duration executionTimeout) throws ModelProviderCallException;
}
