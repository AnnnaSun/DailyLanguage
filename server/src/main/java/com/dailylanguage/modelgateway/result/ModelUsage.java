package com.dailylanguage.modelgateway.result;

/**
 * Provider 能可靠报告的 portable token usage；Gateway 不负责估算缺失数据或计算费用。
 */
public record ModelUsage(long inputTokens, long outputTokens) {

    public ModelUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException("inputTokens must not be negative");
        }
        if (outputTokens < 0) {
            throw new IllegalArgumentException("outputTokens must not be negative");
        }
    }
}
