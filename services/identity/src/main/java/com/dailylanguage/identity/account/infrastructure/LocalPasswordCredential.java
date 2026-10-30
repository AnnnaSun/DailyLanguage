package com.dailylanguage.identity.account.infrastructure;

import java.util.Objects;
import java.util.UUID;

/** 仅在 Identity 内部读取和校验，不作为认证结果或响应返回。 */
record LocalPasswordCredential(UUID userId, String passwordVerifier) {

    LocalPasswordCredential {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(passwordVerifier, "passwordVerifier must not be null");
    }

    @Override
    public String toString() {
        return "LocalPasswordCredential[userId=" + userId + ", passwordVerifier=[REDACTED]]";
    }
}
