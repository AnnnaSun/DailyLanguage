package com.dailylanguage.identity.security;

import java.util.UUID;

import com.dailylanguage.identity.account.AuthenticatedAccount;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只返回已认证账户，不接受请求中的 userId 作为身份依据。 */
@RestController
public final class CurrentAccountController {

    @GetMapping("/api/auth/me")
    CurrentAccountResponse getCurrentAccount(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccount account) {
        return new CurrentAccountResponse(account.userId());
    }

    record CurrentAccountResponse(UUID userId) {
    }
}
