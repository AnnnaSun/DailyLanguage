package com.dailylanguage.identity.security.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.dailylanguage.identity.account.domain.AuthenticatedAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

/** 复用现有最小 Web 配置验证 Session，不需要账户数据库或新增登录端点。 */
@SpringBootTest(classes = IdentityBoundaryHttpTests.TestApplication.class,
        properties = "IDENTITY_SESSION_COOKIE_SECURE=true")
@EnabledIfEnvironmentVariable(named = "IDENTITY_REDIS_TESTS", matches = "true")
class RedisSessionIntegrationTests {

    @Autowired
    private RedisSessionRepository sessions;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ApplicationContext application;
    @Autowired
    private ServerProperties server;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void ignoresPreviousNamespaceWithoutDeletingItsStoredSession() {
        var previousRepository = new RedisSessionRepository(sessions.getSessionRedisOperations());
        previousRepository.setRedisKeyNamespace("daily-language:identity:session:v1");
        Session previousSession = previousRepository.createSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new AuthenticatedAccount(UUID.randomUUID()), null, List.of()));
        previousSession.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        String key = "daily-language:identity:session:v1:sessions:" + previousSession.getId();
        String attribute = "sessionAttr:" + SPRING_SECURITY_CONTEXT_KEY;
        try {
            ((SessionRepository) previousRepository).save(previousSession);
            String serialized = (String) redis.opsForHash().get(key, attribute);
            assertThat(serialized).contains("com.dailylanguage.identity.account.domain.AuthenticatedAccount");
            // 保留旧类型信息，验证 v2 不会尝试恢复旧会话，也不删除其存储内容。
            String previousJson = serialized.replace("com.dailylanguage.identity.account.domain.AuthenticatedAccount",
                    "com.dailylanguage.identity.account.AuthenticatedAccount");
            redis.opsForHash().put(key, attribute, previousJson);

            assertThat((Session) sessions.findById(previousSession.getId())).isNull();
            assertThat(redis.hasKey(key)).isTrue();
            assertThat(redis.opsForHash().get(key, attribute)).isEqualTo(previousJson);
            assertThat(redis.getExpire(key)).isPositive();
        }
        finally {
            previousRepository.deleteById(previousSession.getId());
        }
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void restoresSecurityContextFromRedisWithIsolatedNamespaceAndBoundedLifetime() {
        assertThat(application.getBeansOfType(SessionRepository.class)).hasSize(1).containsValue(sessions);
        var account = new AuthenticatedAccount(UUID.randomUUID());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
        Session session = sessions.createSession();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, context);
        String key = "daily-language:identity:session:v2:sessions:" + session.getId();
        try {
            // RedisSession 的具体类型不可见，使用框架公开的泛型 repository 合同保存。
            ((SessionRepository) sessions).save(session);
            var reader = new RedisSessionRepository(sessions.getSessionRedisOperations());
            reader.setRedisKeyNamespace("daily-language:identity:session:v2");
            Session restored = reader.findById(session.getId());
            assertThat(restored).isNotNull();
            assertThat(restored.getMaxInactiveInterval()).isEqualTo(Duration.ofHours(24));
            SecurityContext restoredContext = restored.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
            assertThat(restoredContext.getAuthentication().getPrincipal()).isEqualTo(account);
            assertThat(restoredContext.getAuthentication().getCredentials()).isNull();
            assertThat(restoredContext.getAuthentication().isAuthenticated()).isTrue();
            assertThat(redis.opsForHash().get(key, "sessionAttr:" + SPRING_SECURITY_CONTEXT_KEY))
                    .asString().contains("AuthenticatedAccount", account.userId().toString());
            assertThat(redis.getExpire(key)).isBetween(23 * 3600L, 24 * 3600L);
            Cookie cookie = server.getServlet().getSession().getCookie();
            assertThat(cookie.getName()).isEqualTo("SESSION");
            assertThat(cookie.getHttpOnly()).isTrue();
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo(Cookie.SameSite.LAX);
            assertThat(cookie.getPath()).isEqualTo("/");
            assertThat(cookie.getDomain()).isNull();
            assertThat(cookie.getMaxAge()).isNull();
        }
        finally {
            sessions.deleteById(session.getId());
        }
        assertThat(redis.hasKey(key)).isFalse();
    }
}
