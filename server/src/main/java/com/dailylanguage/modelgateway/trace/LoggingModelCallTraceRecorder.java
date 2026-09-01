package com.dailylanguage.modelgateway.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;

/** 使用默认 INFO level 输出不包含私密内容的 Model call metadata。 */
public final class LoggingModelCallTraceRecorder implements ModelCallTraceRecorder {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingModelCallTraceRecorder.class);

    @Override
    public void record(ModelCallTrace trace) {
        String providerId = trace.providerId().map(ProviderId::value).orElse("-");
        String modelId = trace.modelId().map(ModelId::value).orElse("-");
        String failureKind = trace.failureKind().map(Enum::name).orElse("-");
        String finishReason = trace.finishReason().map(Enum::name).orElse("-");
        String inputTokens = trace.usage()
                .map(ModelUsage::inputTokens)
                .map(String::valueOf)
                .orElse("-");
        String outputTokens = trace.usage()
                .map(ModelUsage::outputTokens)
                .map(String::valueOf)
                .orElse("-");

        LOGGER.info(
                "Model call trace traceId={} purpose={} providerId={} modelId={} status={} "
                        + "failureKind={} finishReason={} inputTokens={} outputTokens={} gatewayLatencyMs={}",
                trace.traceId(),
                trace.purpose(),
                providerId,
                modelId,
                trace.status(),
                failureKind,
                finishReason,
                inputTokens,
                outputTokens,
                trace.gatewayLatency().toMillis());
    }
}
