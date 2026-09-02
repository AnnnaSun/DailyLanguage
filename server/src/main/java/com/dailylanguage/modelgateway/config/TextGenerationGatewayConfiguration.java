package com.dailylanguage.modelgateway.config;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ModelRouteKey;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.modelgateway.text.execution.RoutedTextGenerationPort;
import com.dailylanguage.modelgateway.text.execution.TextGenerationRoute;
import com.dailylanguage.modelgateway.text.openaicompatible.OpenAiCompatibleProviderConfig;
import com.dailylanguage.modelgateway.text.openaicompatible.OpenAiCompatibleTextGenerationAdapter;
import com.dailylanguage.modelgateway.trace.LoggingModelCallTraceRecorder;
import com.dailylanguage.modelgateway.trace.ModelCallTraceRecorder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 将 trusted deployment configuration 组合成可注入的 Text Generation Gateway runtime。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TextGenerationGatewayProperties.class)
public class TextGenerationGatewayConfiguration {

    static final String MODEL_PROVIDER_HTTP_CLIENT = "modelProviderHttpClient";
    static final String MODEL_CALL_EXECUTOR = "modelCallExecutor";

    @Bean
    OpenAiCompatibleProviderConfig openAiCompatibleProviderConfig(
            TextGenerationGatewayProperties properties) {
        TextGenerationGatewayProperties.OpenAiCompatibleProviderSettings provider =
                properties.openAiCompatibleProvider();
        return new OpenAiCompatibleProviderConfig(
                new ProviderId(provider.providerId()),
                provider.chatCompletionsEndpoint());
    }

    @Bean(name = MODEL_PROVIDER_HTTP_CLIENT)
    HttpClient modelProviderHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    OpenAiCompatibleTextGenerationAdapter openAiCompatibleTextGenerationAdapter(
            OpenAiCompatibleProviderConfig providerConfig,
            @Qualifier(MODEL_PROVIDER_HTTP_CLIENT) HttpClient httpClient,
            JsonMapper jsonMapper) {
        return new OpenAiCompatibleTextGenerationAdapter(providerConfig, httpClient, jsonMapper);
    }

    @Bean
    FixedTextGenerationRoutes fixedTextGenerationRoutes(
            TextGenerationGatewayProperties properties,
            OpenAiCompatibleProviderConfig providerConfig,
            OpenAiCompatibleTextGenerationAdapter adapter) {
        Map<ModelRouteKey, TextGenerationRoute> routes = new HashMap<>();
        for (Map.Entry<ModelPurpose, TextGenerationGatewayProperties.RouteSettings> entry
                : properties.routes().entrySet()) {
            TextGenerationGatewayProperties.RouteSettings routeSettings = entry.getValue();
            TextGenerationRoute route = new TextGenerationRoute(
                    providerConfig.providerId(),
                    new ModelId(routeSettings.modelId()),
                    adapter,
                    routeSettings.executionTimeout());
            routes.put(new ModelRouteKey(entry.getKey(), ModelOperation.TEXT_GENERATION), route);
        }
        return new FixedTextGenerationRoutes(routes);
    }

    // 单次 Provider deadline 的 worker 与 Job TaskExecutor 隔离，避免 Job worker 提交调用后等待自己的线程池。
    @Bean(name = MODEL_CALL_EXECUTOR, destroyMethod = "shutdown")
    ExecutorService modelCallExecutor(TextGenerationGatewayProperties properties) {
        TextGenerationGatewayProperties.ExecutorSettings executor = properties.executor();
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name("model-call-", 0)
                .factory();
        return new ThreadPoolExecutor(
                executor.workers(),
                executor.workers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(executor.queueCapacity()),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    ModelCallTraceRecorder modelCallTraceRecorder() {
        return new LoggingModelCallTraceRecorder();
    }

    @Bean
    TextGenerationPort textGenerationPort(
            FixedTextGenerationRoutes routes,
            @Qualifier(MODEL_CALL_EXECUTOR) ExecutorService modelCallExecutor,
            ModelCallTraceRecorder traceRecorder) {
        return new RoutedTextGenerationPort(routes, modelCallExecutor, traceRecorder);
    }
}
