package com.dailylanguage.modelgateway.text;

import java.util.Objects;
import java.util.Optional;

import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;

/**
 * 成功完成一次 Text Generation 后可供业务使用的 portable response，不暴露 Provider raw response。
 * {@code text} 始终非 null；Provider 没有产生可见输出时可以为空，由 finish reason 说明结束状态。
 */
public record TextGenerationResponse(
        ProviderId providerId,
        ModelId modelId,
        String text,
        FinishReason finishReason,
        Optional<ModelUsage> usage) {

    public TextGenerationResponse {
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(finishReason, "finishReason must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
    }

    public enum FinishReason {
        /** Model 正常结束生成。 */
        COMPLETED,

        /** Model 因输出长度限制停止。 */
        LENGTH_LIMIT,

        /** Model 输出被 Provider content policy 过滤或中止。 */
        CONTENT_FILTERED,

        /** Provider 未提供结束原因，或 Adapter 无法可靠归一化。 */
        UNKNOWN
    }
}
