package com.dailylanguage.modelgateway.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.result.ModelFailure;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;
import com.dailylanguage.modelgateway.text.openaicompatible.OpenAiCompatibleProviderConfig;
import com.dailylanguage.modelgateway.text.openaicompatible.OpenAiCompatibleTextGenerationAdapter;
import tools.jackson.databind.json.JsonMapper;

class TextGenerationGatewayConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withUserConfiguration(TextGenerationGatewayConfiguration.class);

    @Test
    void composesDefaultDeepSeekTextGenerationRuntime() {
        AtomicReference<ExecutorService> executorReference = new AtomicReference<>();

        contextRunner.run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(TextGenerationPort.class)
                    .hasSingleBean(OpenAiCompatibleTextGenerationAdapter.class)
                    .hasSingleBean(FixedTextGenerationRoutes.class);

            OpenAiCompatibleProviderConfig providerConfig =
                    context.getBean(OpenAiCompatibleProviderConfig.class);
            assertThat(providerConfig.providerId()).isEqualTo(new ProviderId("deepseek"));
            assertThat(providerConfig.chatCompletionsEndpoint()).isEqualTo(
                    URI.create("https://api.deepseek.com/chat/completions"));

            FixedTextGenerationRoutes routes = context.getBean(FixedTextGenerationRoutes.class);
            TextGenerationRoute conversationRoute = routes.findRoute(ModelPurpose.CONVERSATION)
                    .orElseThrow();
            assertThat(conversationRoute.providerId()).isEqualTo(new ProviderId("deepseek"));
            assertThat(conversationRoute.modelId()).isEqualTo(new ModelId("deepseek-chat"));
            assertThat(conversationRoute.executionTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(conversationRoute.adapter())
                    .isSameAs(context.getBean(OpenAiCompatibleTextGenerationAdapter.class));
            TextGenerationRoute verificationRoute = routes.findRoute(
                            ModelPurpose.CONNECTION_VERIFICATION)
                    .orElseThrow();
            assertThat(verificationRoute.providerId()).isEqualTo(new ProviderId("deepseek"));
            assertThat(verificationRoute.modelId()).isEqualTo(new ModelId("deepseek-chat"));
            assertThat(verificationRoute.executionTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(verificationRoute.adapter()).isSameAs(conversationRoute.adapter());
            assertThat(routes.findRoute(ModelPurpose.PLANNING)).isEmpty();

            HttpClient httpClient = context.getBean(
                    TextGenerationGatewayConfiguration.MODEL_PROVIDER_HTTP_CLIENT,
                    HttpClient.class);
            assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);

            ExecutorService executor = context.getBean(
                    TextGenerationGatewayConfiguration.MODEL_CALL_EXECUTOR,
                    ExecutorService.class);
            executorReference.set(executor);
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
            assertThat(threadPoolExecutor.getCorePoolSize()).isEqualTo(4);
            assertThat(threadPoolExecutor.getMaximumPoolSize()).isEqualTo(4);
            assertThat(threadPoolExecutor.getQueue().remainingCapacity()).isEqualTo(16);
        });

        assertThat(executorReference.get()).isNotNull();
        assertThat(executorReference.get().isShutdown()).isTrue();
    }

    @Test
    void switchesToOpenAiThroughConfigurationWithoutAnotherAdapter() {
        contextRunner
                .withPropertyValues(
                        "app.model-gateway.text-generation.open-ai-compatible-provider.provider-id=openai",
                        "app.model-gateway.text-generation.open-ai-compatible-provider.chat-completions-endpoint=https://api.openai.com/v1/chat/completions",
                        "app.model-gateway.text-generation.routes.connection-verification.model-id=gpt-5-mini",
                        "app.model-gateway.text-generation.routes.conversation.model-id=gpt-5-mini")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(OpenAiCompatibleTextGenerationAdapter.class);

                    OpenAiCompatibleProviderConfig providerConfig =
                            context.getBean(OpenAiCompatibleProviderConfig.class);
                    assertThat(providerConfig.providerId()).isEqualTo(new ProviderId("openai"));
                    assertThat(providerConfig.chatCompletionsEndpoint()).isEqualTo(
                            URI.create("https://api.openai.com/v1/chat/completions"));

                    FixedTextGenerationRoutes routes = context.getBean(FixedTextGenerationRoutes.class);
                    TextGenerationRoute route = routes.findRoute(ModelPurpose.CONVERSATION).orElseThrow();
                    assertThat(route.providerId()).isEqualTo(new ProviderId("openai"));
                    assertThat(route.modelId()).isEqualTo(new ModelId("gpt-5-mini"));
                    TextGenerationRoute verificationRoute = routes.findRoute(
                                    ModelPurpose.CONNECTION_VERIFICATION)
                            .orElseThrow();
                    assertThat(verificationRoute.providerId()).isEqualTo(new ProviderId("openai"));
                    assertThat(verificationRoute.modelId()).isEqualTo(new ModelId("gpt-5-mini"));
                });
    }

    @Test
    void unconfiguredPurposeReturnsCapabilityUnavailableWithoutSubmittingProviderCall() {
        contextRunner.run(context -> {
            TextGenerationPort port = context.getBean(TextGenerationPort.class);
            TextGenerationRequest request = new TextGenerationRequest(
                    ModelPurpose.PLANNING,
                    List.of(new TextMessage(TextMessage.Role.USER, "Plan today's practice.")),
                    TextOutputSpecification.plainText());
            TransientProviderCredential credential = new TransientProviderCredential(
                    new ProviderId("deepseek"),
                    "not-sent-to-provider");

            ModelResult<TextGenerationResponse> result = port.generateText(request, credential);

            assertThat(result).isEqualTo(ModelResult.failure(
                    ModelFailure.withoutRoute(ModelFailureKind.CAPABILITY_UNAVAILABLE)));
            ThreadPoolExecutor executor = (ThreadPoolExecutor) context.getBean(
                    TextGenerationGatewayConfiguration.MODEL_CALL_EXECUTOR,
                    ExecutorService.class);
            assertThat(executor.getTaskCount()).isZero();
        });
    }

    @Test
    void boundedExecutorRejectsWorkWhenWorkerAndQueueAreOccupied() throws InterruptedException {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        try {
            contextRunner
                    .withPropertyValues(
                            "app.model-gateway.text-generation.executor.workers=1",
                            "app.model-gateway.text-generation.executor.queue-capacity=1")
                    .run(context -> {
                        ExecutorService executor = context.getBean(
                                TextGenerationGatewayConfiguration.MODEL_CALL_EXECUTOR,
                                ExecutorService.class);
                        executor.submit(() -> {
                            workerStarted.countDown();
                            await(releaseWorker);
                        });
                        assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                        executor.submit(() -> { });

                        assertThatThrownBy(() -> executor.submit(() -> { }))
                                .isInstanceOf(RejectedExecutionException.class);
                    });
        } finally {
            releaseWorker.countDown();
        }
    }

    @Test
    void invalidEndpointPreventsRuntimeStartup() {
        contextRunner
                .withPropertyValues(
                        "app.model-gateway.text-generation.open-ai-compatible-provider.chat-completions-endpoint=http://api.deepseek.com/chat/completions")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessage("chatCompletionsEndpoint must be an absolute HTTPS URI"));
    }

    @Test
    void nonPositiveTimeoutOrExecutorCapacityPreventsRuntimeStartup() {
        contextRunner
                .withPropertyValues(
                        "app.model-gateway.text-generation.routes.conversation.execution-timeout=0s")
                .run(context -> assertThat(context).hasFailed());

        contextRunner
                .withPropertyValues(
                        "app.model-gateway.text-generation.executor.workers=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
