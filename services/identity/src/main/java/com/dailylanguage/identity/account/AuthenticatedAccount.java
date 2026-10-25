package com.dailylanguage.identity.account;

import java.util.Objects;
import java.util.UUID;

/** 凭据认证后的账户身份；不表示获得任何应用准入或业务资源权限。 */
public record AuthenticatedAccount(UUID userId) {

    public AuthenticatedAccount {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
