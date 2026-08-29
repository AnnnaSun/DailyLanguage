package com.dailylanguage.modelgateway.text;

import com.dailylanguage.modelgateway.result.ModelResult;

/**
 * 业务模块发起单次 Text Generation 的 typed boundary；具体 routing 与 Provider execution 由后续实现负责。
 */
public interface TextGenerationPort {

    ModelResult<TextGenerationResponse> generateText(TextGenerationRequest request);
}
