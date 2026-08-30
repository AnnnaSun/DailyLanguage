package com.dailylanguage.modelgateway.text;

import com.dailylanguage.modelgateway.result.ModelResult;

/**
 * 业务模块发起单次 Text Generation 的 typed boundary；当前 routed implementation 负责
 * fixed routing、Adapter execution、final timeout 与 safe failure translation，concrete Provider integration 仍在该边界之后。
 */
public interface TextGenerationPort {

    ModelResult<TextGenerationResponse> generateText(TextGenerationRequest request);
}
