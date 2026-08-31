# OpenAI-compatible Text Provider Call Flow

- Document Status: `IMPLEMENTED`
- Feature / Slice: `M0-S7B / M0-S7C`
- Last Verified: `2026-08-31`
- Entry: `OpenAiCompatibleTextGenerationAdapter.generateText(...)`

## 1. Behavior Boundary

本 Flow 描述已经实现的 concrete Provider protocol boundary：一个已经由 fixed route 选中的
OpenAI-compatible Text Adapter 使用 matching transient Credential 发起 non-streaming Chat Completions HTTP
request，并把安全、portable 的结果或 typed failure 返回 Gateway。

第一个配置目标是 DeepSeek。S7C 已增加 Spring runtime wiring，但当前没有 Browser / HTTPS Credential ingress、
Application Workflow 或 live DeepSeek network verification，因此本 Flow 仍不是 BYOK End-to-End behavior。

## 2. Runtime Composition

`application.yml` 显式导入 `model-gateway.yml`，`TextGenerationGatewayConfiguration` 在 startup 使用其中的
typed deployment properties 组成一套 runtime：默认 route
只有 `CONVERSATION → deepseek / deepseek-chat / 30s`，并注入同一个 OpenAI-compatible Adapter、禁止 redirect
的 JDK HttpClient，以及独立 bounded model-call ExecutorService（默认 4 workers / 16 queue）。这一过程不读取
Credential，也不发起网络请求；切换到 OpenAI 只替换 ProviderId、endpoint 与 ModelId 配置。

## 3. Main Call Chain

```mermaid
sequenceDiagram
    participant Worker as Executor worker
    participant Adapter as OpenAiCompatibleTextGenerationAdapter
    participant Mapper as OpenAiCompatibleTextPayloadMapper
    participant HTTP as JDK HttpClient
    participant Provider as Configured HTTPS Provider

    Worker->>Adapter: generateText(providerId, modelId, request, credential, timeout)
    Adapter->>Adapter: validate configured Provider and Credential identity
    Adapter->>Mapper: writeRequest(modelId, portable request)
    Mapper-->>Adapter: non-streaming Chat Completions JSON
    Adapter->>HTTP: POST + Bearer credential + request timeout
    HTTP->>Provider: configured HTTPS /chat/completions endpoint
    alt HTTP or transport failure
        Provider-->>HTTP: status / transport failure
        HTTP-->>Adapter: response or safe local exception
        Adapter-->>Worker: ModelProviderCallException(kind, optional retryAfter)
    else 2xx response
        Provider-->>HTTP: Chat Completions JSON
        HTTP-->>Adapter: status + body
        Adapter->>Mapper: readResponse(selected ProviderId / ModelId, body)
        Mapper-->>Adapter: portable TextGenerationResponse
        Adapter-->>Worker: ModelResult.Success(response)
    end
```

## 4. State and Authority

- `OpenAiCompatibleProviderConfig` owns the trusted ProviderId and HTTPS endpoint value；
- `TextGenerationRoute` remains the selected ModelId authority；Provider raw `model` does not replace it；
- `TransientProviderCredential.secret()` is read only to build the outbound Bearer header；
- Adapter and mapper do not write PostgreSQL, Redis, Trace, logs, or Learning State；
- the injected `HttpClient` must use `Redirect.NEVER`, so Credential is not forwarded by redirect.

## 5. Failure / Rejection Paths

- configured Provider / Credential identity mismatch: fail fast before HTTP；
- unsafe header value: `AUTHENTICATION_FAILED` without message or cause；
- 401 / 403: `AUTHENTICATION_FAILED`；408 or client timeout: `TIMEOUT`；
- 429: `RATE_LIMITED`；5xx / transport unavailable: `TEMPORARY_UNAVAILABLE`；
- other 4xx: `REQUEST_REJECTED`；unclassified status or malformed 2xx payload: `PROVIDER_FAILURE`；
- positive numeric `Retry-After` is retained only for rate limit / temporary unavailable；invalid raw value is dropped；
- Provider error body, raw response, Prompt, Credential, transport / parsing message and cause do not cross the Adapter.

## 6. Verification Evidence

- `OpenAiCompatibleProviderConfigTests`: trusted DeepSeek HTTPS endpoint and invalid endpoint rejection；
- `OpenAiCompatibleTextPayloadMapperTests`: role/request mapping, selected route identity, finish reason, usage and
  malformed payload safety；
- `OpenAiCompatibleTextGenerationAdapterTests`: Bearer header, timeout, redirect rejection, HTTP / transport failure,
  Retry-After, interrupt restoration and secret-safe failure；
- `TextGenerationGatewayConfigurationTests`: imported configuration resource、default DeepSeek binding、OpenAI
  override、bounded executor 与 invalid configuration；
- S7C configuration tests: 6/6 PASS；Model Gateway regression: 67/67 PASS；
- server regression: 207 total / 0 failures / 0 errors / 33 environment-skipped.

## 7. Source References

- `server/src/main/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleProviderConfig.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleTextGenerationAdapter.java`
- `server/src/main/java/com/dailylanguage/modelgateway/text/openaicompatible/OpenAiCompatibleTextPayloadMapper.java`
- `server/src/main/java/com/dailylanguage/modelgateway/infrastructure/TextGenerationGatewayProperties.java`
- `server/src/main/java/com/dailylanguage/modelgateway/infrastructure/TextGenerationGatewayConfiguration.java`
- `server/src/main/resources/application.yml`
- `server/src/main/resources/model-gateway.yml`
- `server/src/test/java/com/dailylanguage/modelgateway/text/openaicompatible/`
- `server/src/test/java/com/dailylanguage/modelgateway/infrastructure/TextGenerationGatewayConfigurationTests.java`
