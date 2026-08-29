package com.dailylanguage.modelgateway.result;

/**
 * 业务调用方可以据此选择明确的 degraded behavior，不需要解析 Provider exception。
 */
public enum ModelFailureKind {
    /** 当前请求没有可用 route，或选定 Provider / Model 不具备所需 capability。 */
    CAPABILITY_UNAVAILABLE,

    /** 请求已到达 Model boundary，但不满足 portable contract 或 Provider request policy。 */
    REQUEST_REJECTED,

    /** Provider 拒绝当前 Credential；不得携带 Credential 或底层认证响应。 */
    AUTHENTICATION_FAILED,

    /** Provider 对当前 route 实施限流，可以携带正数 retryAfter 提示。 */
    RATE_LIMITED,

    /** Model 调用超过 Gateway 规定的等待时间。 */
    TIMEOUT,

    /** Provider 或 Model 暂时不可用，可以携带正数 retryAfter 提示。 */
    TEMPORARY_UNAVAILABLE,

    /** 已调用 Provider，但失败无法安全、准确地归入更具体的类别。 */
    PROVIDER_FAILURE
}
