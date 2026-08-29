package com.dailylanguage.security.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 认证完成后交给 Domain 层使用的可信 request identity。
 * 业务代码只能从该对象取得 userId，不得用客户端参数重建用户身份。
 */
public record UserContext(UUID userId) {

    public UserContext {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
