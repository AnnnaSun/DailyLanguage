package com.dailylanguage.security.api;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dailylanguage.security.domain.UserContext;

/**
 * 返回当前 SecurityContext 中已经认证的用户身份，不接受客户端指定查询目标。
 */
@RestController
@RequestMapping("/api/auth")
public final class CurrentUserController {

    @GetMapping("/me")
    CurrentUserResponse getCurrentUser(
            @AuthenticationPrincipal(errorOnInvalidType = true) UserContext authenticatedUser) {
        return new CurrentUserResponse(authenticatedUser.userId());
    }

    record CurrentUserResponse(UUID userId) {
    }
}
