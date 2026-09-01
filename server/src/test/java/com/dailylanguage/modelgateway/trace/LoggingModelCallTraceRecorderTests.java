package com.dailylanguage.modelgateway.trace;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.result.ModelUsage;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingModelCallTraceRecorderTests {

    @Test
    void logsOnlySafeModelCallMetadataAtInfoLevel() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingModelCallTraceRecorder.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            TextGenerationResponse response = new TextGenerationResponse(
                    new ProviderId("deepseek"),
                    new ModelId("deepseek-v4-flash"),
                    "private generated text",
                    TextGenerationResponse.FinishReason.COMPLETED,
                    Optional.of(new ModelUsage(8, 3)));
            ModelCallTrace trace = ModelCallTrace.fromResult(
                    UUID.fromString("9c40e8cb-9bf5-4c29-aac6-22f976d919ab"),
                    ModelPurpose.CONNECTION_VERIFICATION,
                    Duration.ofMillis(125),
                    ModelResult.success(response));

            new LoggingModelCallTraceRecorder().record(trace);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.INFO);
                assertThat(event.getFormattedMessage())
                        .contains(
                                "traceId=9c40e8cb-9bf5-4c29-aac6-22f976d919ab",
                                "purpose=CONNECTION_VERIFICATION",
                                "providerId=deepseek",
                                "modelId=deepseek-v4-flash",
                                "status=SUCCESS",
                                "inputTokens=8",
                                "outputTokens=3",
                                "gatewayLatencyMs=125")
                        .doesNotContain("private generated text", "Credential", "Authorization");
            });
        }
        finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }
}
