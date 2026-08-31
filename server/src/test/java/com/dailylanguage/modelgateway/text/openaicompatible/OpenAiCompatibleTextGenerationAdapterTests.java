package com.dailylanguage.modelgateway.text.openaicompatible;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.execution.ModelProviderCallException;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleTextGenerationAdapterTests {

    private static final ProviderId DEEPSEEK_PROVIDER_ID = new ProviderId("deepseek");
    private static final ModelId MODEL_ID = new ModelId("deepseek-model");
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final Duration EXECUTION_TIMEOUT = Duration.ofSeconds(20);
    private static final String SECRET = "deepseek-test-secret";
    private static final TransientProviderCredential CREDENTIAL =
            new TransientProviderCredential(DEEPSEEK_PROVIDER_ID, SECRET);
    private static final TextGenerationRequest REQUEST = new TextGenerationRequest(
            ModelPurpose.CONVERSATION,
            List.of(new TextMessage(TextMessage.Role.USER, "Help me order food.")),
            TextOutputSpecification.plainText());

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void sendsOneDeepSeekCompatibleRequestAndReturnsPortableSelectedRouteIdentity() throws Exception {
        HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
        respond(httpClient, 200, """
                {
                  "model": "raw-provider-model-version",
                  "choices": [{"message": {"content": "What would you like?"}, "finish_reason": "stop"}]
                }
                """, Map.of());

        ModelResult<TextGenerationResponse> result = adapter(httpClient).generateText(
                DEEPSEEK_PROVIDER_ID, MODEL_ID, REQUEST, CREDENTIAL, EXECUTION_TIMEOUT);

        assertThat(result).isEqualTo(ModelResult.success(new TextGenerationResponse(
                DEEPSEEK_PROVIDER_ID,
                MODEL_ID,
                "What would you like?",
                TextGenerationResponse.FinishReason.COMPLETED,
                Optional.empty())));
        HttpRequest sentRequest = sentRequest(httpClient);
        assertThat(sentRequest.uri()).isEqualTo(ENDPOINT);
        assertThat(sentRequest.method()).isEqualTo("POST");
        assertThat(sentRequest.timeout()).contains(EXECUTION_TIMEOUT);
        assertThat(sentRequest.headers().firstValue("Content-Type")).contains("application/json");
        assertThat(sentRequest.headers().firstValue("Authorization")).contains("Bearer " + SECRET);
        assertThat(sentRequest.bodyPublisher()).isPresent();
    }

    @Test
    void classifiesProviderHttpFailuresWithoutUsingTheirBodies() {
        Map<Integer, ModelFailureKind> expectedKinds = Map.of(
                302, ModelFailureKind.PROVIDER_FAILURE,
                400, ModelFailureKind.REQUEST_REJECTED,
                401, ModelFailureKind.AUTHENTICATION_FAILED,
                408, ModelFailureKind.TIMEOUT,
                429, ModelFailureKind.RATE_LIMITED,
                503, ModelFailureKind.TEMPORARY_UNAVAILABLE);

        expectedKinds.forEach((statusCode, expectedKind) -> {
            HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
            respond(httpClient, statusCode, "{\"unsafe_detail\":\"" + SECRET + "\"}", Map.of());

            ModelProviderCallException failure = providerFailure(httpClient);

            assertThat(failure.kind()).isEqualTo(expectedKind);
            assertThat(failure.getMessage()).isNull();
            assertThat(failure.getCause()).isNull();
        });
    }

    @Test
    void safelyParsesPositiveRetryAfterForRateLimitAndTemporaryUnavailable() {
        HttpClient rateLimitedClient = httpClient(HttpClient.Redirect.NEVER);
        respond(rateLimitedClient, 429, "ignored", Map.of("Retry-After", List.of("12")));
        HttpClient unavailableClient = httpClient(HttpClient.Redirect.NEVER);
        respond(unavailableClient, 503, "ignored", Map.of("Retry-After", List.of("30")));

        ModelProviderCallException rateLimited = providerFailure(rateLimitedClient);
        ModelProviderCallException unavailable = providerFailure(unavailableClient);

        assertThat(rateLimited.kind()).isEqualTo(ModelFailureKind.RATE_LIMITED);
        assertThat(rateLimited.retryAfter()).contains(Duration.ofSeconds(12));
        assertThat(unavailable.kind()).isEqualTo(ModelFailureKind.TEMPORARY_UNAVAILABLE);
        assertThat(unavailable.retryAfter()).contains(Duration.ofSeconds(30));
    }

    @Test
    void dropsInvalidRetryAfterInsteadOfExposingRawHeaderData() {
        HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
        respond(httpClient, 429, "ignored", Map.of("Retry-After", List.of("private-invalid-value")));

        ModelProviderCallException failure = providerFailure(httpClient);

        assertThat(failure.kind()).isEqualTo(ModelFailureKind.RATE_LIMITED);
        assertThat(failure.retryAfter()).isEmpty();
        assertThat(failure.getMessage()).isNull();
    }

    @Test
    void translatesTransportTimeoutAndUnavailableWithoutTheirMessagesOrCauses() {
        HttpClient timeoutClient = httpClient(HttpClient.Redirect.NEVER);
        failWith(timeoutClient, new HttpTimeoutException("unsafe-timeout-" + SECRET));
        HttpClient unavailableClient = httpClient(HttpClient.Redirect.NEVER);
        failWith(unavailableClient, new IOException("unsafe-transport-" + SECRET));

        ModelProviderCallException timeout = providerFailure(timeoutClient);
        ModelProviderCallException unavailable = providerFailure(unavailableClient);

        assertThat(timeout.kind()).isEqualTo(ModelFailureKind.TIMEOUT);
        assertThat(timeout.getMessage()).isNull();
        assertThat(timeout.getCause()).isNull();
        assertThat(unavailable.kind()).isEqualTo(ModelFailureKind.TEMPORARY_UNAVAILABLE);
        assertThat(unavailable.getMessage()).isNull();
        assertThat(unavailable.getCause()).isNull();
    }

    @Test
    void rejectsAnUnsafeBearerHeaderWithoutSendingOrExposingTheCredential() throws Exception {
        HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
        TransientProviderCredential unsafeCredential = new TransientProviderCredential(
                DEEPSEEK_PROVIDER_ID,
                "secret\r\ninjected-header");

        ModelProviderCallException failure = catchThrowableOfType(
                ModelProviderCallException.class,
                () -> adapter(httpClient).generateText(
                        DEEPSEEK_PROVIDER_ID, MODEL_ID, REQUEST, unsafeCredential, EXECUTION_TIMEOUT));

        assertThat(failure.kind()).isEqualTo(ModelFailureKind.AUTHENTICATION_FAILED);
        assertThat(failure.getMessage()).isNull();
        assertThat(failure.getCause()).isNull();
        verifyNoSend(httpClient);
    }

    @Test
    void rejectsANullSuccessfulBodyAsAProviderFailure() {
        HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
        respond(httpClient, 200, null, Map.of());

        ModelProviderCallException failure = providerFailure(httpClient);

        assertThat(failure.kind()).isEqualTo(ModelFailureKind.PROVIDER_FAILURE);
        assertThat(failure.getMessage()).isNull();
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void failsFastBeforeHttpWhenProviderConfigurationOrCredentialDoesNotMatch() throws Exception {
        HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
        OpenAiCompatibleTextGenerationAdapter adapter = adapter(httpClient);

        assertThatIllegalStateException()
                .isThrownBy(() -> adapter.generateText(
                        new ProviderId("openai"),
                        MODEL_ID,
                        REQUEST,
                        new TransientProviderCredential(new ProviderId("openai"), "openai-secret"),
                        EXECUTION_TIMEOUT))
                .withMessage("adapter provider does not match configured provider");
        assertThatIllegalStateException()
                .isThrownBy(() -> adapter.generateText(
                        DEEPSEEK_PROVIDER_ID,
                        MODEL_ID,
                        REQUEST,
                        new TransientProviderCredential(new ProviderId("openai"), "openai-secret"),
                        EXECUTION_TIMEOUT))
                .withMessage("adapter credential does not match selected provider");
        verifyNoSend(httpClient);
    }

    @Test
    void rejectsAnHttpClientThatCouldForwardCredentialThroughRedirects() {
        HttpClient redirectingClient = httpClient(HttpClient.Redirect.ALWAYS);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> adapter(redirectingClient))
                .withMessage("OpenAI-compatible HttpClient must not follow redirects");
    }

    @Test
    void restoresWorkerInterruptAndFailsWithoutProviderDetail() {
        HttpClient httpClient = httpClient(HttpClient.Redirect.NEVER);
        failWith(httpClient, new InterruptedException("unsafe-interrupt-detail"));

        assertThatIllegalStateException()
                .isThrownBy(() -> adapter(httpClient).generateText(
                        DEEPSEEK_PROVIDER_ID, MODEL_ID, REQUEST, CREDENTIAL, EXECUTION_TIMEOUT))
                .withMessage("provider HTTP call was interrupted")
                .withNoCause();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private static OpenAiCompatibleTextGenerationAdapter adapter(HttpClient httpClient) {
        OpenAiCompatibleProviderConfig config =
                new OpenAiCompatibleProviderConfig(DEEPSEEK_PROVIDER_ID, ENDPOINT);
        return new OpenAiCompatibleTextGenerationAdapter(
                config,
                httpClient,
                JsonMapper.builder().build());
    }

    private static HttpClient httpClient(HttpClient.Redirect redirect) {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.followRedirects()).thenReturn(redirect);
        return httpClient;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(
            int statusCode,
            String body,
            Map<String, List<String>> rawHeaders) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(
                rawHeaders,
                (headerName, headerValue) -> true));
        return response;
    }

    private static void respond(
            HttpClient httpClient,
            int statusCode,
            String body,
            Map<String, List<String>> rawHeaders) {
        HttpResponse<String> response = response(statusCode, body, rawHeaders);
        try {
            whenSend(httpClient).thenReturn(response);
        }
        catch (IOException | InterruptedException exception) {
            throw new IllegalStateException("failed to configure HTTP test response");
        }
    }

    private static void failWith(HttpClient httpClient, Throwable failure) {
        try {
            whenSend(httpClient).thenThrow(failure);
        }
        catch (IOException | InterruptedException exception) {
            throw new IllegalStateException("failed to configure HTTP test failure");
        }
    }

    private static org.mockito.stubbing.OngoingStubbing<HttpResponse<String>> whenSend(HttpClient httpClient)
            throws IOException, InterruptedException {
        return when(httpClient.send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()));
    }

    private static HttpRequest sentRequest(HttpClient httpClient) throws Exception {
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(
                requestCaptor.capture(),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        return requestCaptor.getValue();
    }

    private static void verifyNoSend(HttpClient httpClient) throws Exception {
        verify(httpClient, never()).send(
                any(HttpRequest.class),
                ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
    }

    private static ModelProviderCallException providerFailure(HttpClient httpClient) {
        return catchThrowableOfType(
                ModelProviderCallException.class,
                () -> adapter(httpClient).generateText(
                        DEEPSEEK_PROVIDER_ID, MODEL_ID, REQUEST, CREDENTIAL, EXECUTION_TIMEOUT));
    }
}
