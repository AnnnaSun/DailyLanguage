package com.dailylanguage.modelgateway.text.openaicompatible;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleFinishReasonDiagnosticsTests {

    private static final UUID TRACE_ID = UUID.fromString("6e699faf-f09b-46cf-9657-1b302296c71c");
    private static final ProviderId PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-v4-flash");

    private final Logger logger =
            (Logger) LoggerFactory.getLogger(OpenAiCompatibleFinishReasonDiagnostics.class);
    private final ThreadSafeAppender appender = new ThreadSafeAppender();
    private Level originalLevel;

    @BeforeEach
    void captureWarnings() {
        originalLevel = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
    }

    @AfterEach
    void stopCapturingWarnings() {
        logger.detachAppender(appender);
        logger.setLevel(originalLevel);
        appender.stop();
    }

    @Test
    void keepsKnownReasonQuietAndDiagnosticFailureCannotReplaceUnknownResponse() throws Exception {
        OpenAiCompatibleTextPayloadMapper normalMapper = new OpenAiCompatibleTextPayloadMapper(
                JsonMapper.builder().build());

        TextGenerationResponse knownResponse = normalMapper.readResponse(
                TRACE_ID,
                PROVIDER_ID,
                MODEL_ID,
                responseWithFinishReason("stop"));

        assertThat(knownResponse.finishReason()).isEqualTo(TextGenerationResponse.FinishReason.COMPLETED);
        assertThat(appender.events()).isEmpty();

        OpenAiCompatibleFinishReasonDiagnostics failingDiagnostics =
                new OpenAiCompatibleFinishReasonDiagnostics(() -> {
                    throw new IllegalStateException("diagnostic failure");
                });
        OpenAiCompatibleTextPayloadMapper failOpenMapper = new OpenAiCompatibleTextPayloadMapper(
                JsonMapper.builder().build(),
                failingDiagnostics);

        TextGenerationResponse unknownResponse = failOpenMapper.readResponse(
                TRACE_ID,
                PROVIDER_ID,
                MODEL_ID,
                responseWithFinishReason("new_reason"));

        assertThat(unknownResponse.finishReason()).isEqualTo(TextGenerationResponse.FinishReason.UNKNOWN);
    }

    @Test
    void classifiesMissingSafeAndInvalidValuesWithoutLeakingInvalidRawData() {
        new OpenAiCompatibleFinishReasonDiagnostics(() -> 0L)
                .reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, null);
        new OpenAiCompatibleFinishReasonDiagnostics(() -> 0L)
                .reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, "insufficient_system_resource");
        String unsafeRawValue = "private\nfinish reason";
        new OpenAiCompatibleFinishReasonDiagnostics(() -> 0L)
                .reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, unsafeRawValue);
        String overlengthRawValue = "a".repeat(65);
        new OpenAiCompatibleFinishReasonDiagnostics(() -> 0L)
                .reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, overlengthRawValue);

        assertThat(appender.events()).hasSize(4);
        assertThat(appender.events().get(0).getFormattedMessage())
                .contains(
                        "traceId=" + TRACE_ID,
                        "providerId=deepseek",
                        "modelId=deepseek-v4-flash",
                        "adapterVersion=openai-compatible-text-v1",
                        "classification=MISSING",
                        "rawFinishReason=-");
        assertThat(appender.events().get(1).getFormattedMessage())
                .contains(
                        "classification=SAFE_TOKEN",
                        "rawFinishReason=insufficient_system_resource");
        String invalidMessage = appender.events().get(2).getFormattedMessage();
        assertThat(invalidMessage)
                .contains(
                        "classification=INVALID",
                        "rawFinishReason=-",
                        "rawLength=" + unsafeRawValue.length())
                .doesNotContain(unsafeRawValue, "private\n")
                .matches(".*rawSha256=[0-9a-f]{64}$");
        assertThat(appender.events().get(3).getFormattedMessage())
                .contains("classification=INVALID", "rawFinishReason=-", "rawLength=65")
                .doesNotContain(overlengthRawValue)
                .matches(".*rawSha256=[0-9a-f]{64}$");
    }

    @Test
    void rateLimitsPerRouteAndReopensAfterOneMinute() {
        AtomicLong currentNanos = new AtomicLong();
        OpenAiCompatibleFinishReasonDiagnostics diagnostics =
                new OpenAiCompatibleFinishReasonDiagnostics(currentNanos::get);

        diagnostics.reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, "first_unknown");
        diagnostics.reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, "suppressed_unknown");
        diagnostics.reportUnknown(
                TRACE_ID,
                PROVIDER_ID,
                new ModelId("another-model"),
                "other_route_unknown");

        assertThat(appender.events()).hasSize(2);

        currentNanos.addAndGet(Duration.ofMinutes(1).toNanos());
        diagnostics.reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, "after_window");

        assertThat(appender.events()).hasSize(3);
        assertThat(appender.events().get(2).getFormattedMessage()).contains("rawFinishReason=after_window");
    }

    @Test
    void allowsOnlyOneConcurrentWarningForTheSameRoute() throws InterruptedException {
        int callers = 8;
        OpenAiCompatibleFinishReasonDiagnostics diagnostics =
                new OpenAiCompatibleFinishReasonDiagnostics(() -> 0L);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(callers);
        try {
            for (int index = 0; index < callers; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        diagnostics.reportUnknown(TRACE_ID, PROVIDER_ID, MODEL_ID, "concurrent_unknown");
                    }
                    catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    finally {
                        completed.countDown();
                    }
                });
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        }
        finally {
            executor.shutdownNow();
        }

        assertThat(appender.events()).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("rawFinishReason=concurrent_unknown"));
    }

    private static String responseWithFinishReason(String finishReason) {
        return """
                {"choices": [{"message": {"content": "text"}, "finish_reason": "%s"}]}
                """.formatted(finishReason);
    }

    private static final class ThreadSafeAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            events.add(event);
        }

        List<ILoggingEvent> events() {
            return List.copyOf(events);
        }
    }
}
