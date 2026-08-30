package com.dailylanguage.modelgateway.text.execution;

import java.time.Duration;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

/**
 * 单个 Provider 执行 Text Generation 的 operation-specific SPI，不暴露 Provider SDK type。
 */
public interface TextGenerationProviderAdapter {

    ModelResult<TextGenerationResponse> generateText(
            ProviderId providerId,
            ModelId modelId,
            TextGenerationRequest request,
            TransientProviderCredential credential,
            Duration executionTimeout) throws ModelProviderCallException;
}
