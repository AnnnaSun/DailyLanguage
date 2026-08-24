package com.dailylanguage.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.web.context.HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "SESSION_COOKIE_SECURE=true"
})
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_TESTS", matches = "true")
class RedisSessionIntegrationTests {

    private static final String SESSION_KEY_PREFIX = "daily-language:session:v1:sessions:";

    @Autowired
    private RedisSessionRepository sessionRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ServerProperties serverProperties;

    @Test
    void storesAndRestoresSecurityContextWithTheConfiguredNamespaceAndTimeout() {
        UUID userId = UUID.randomUUID();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()));

        Session session = sessionRepository.createSession();
        String sessionId = session.getId();
        session.setAttribute(SPRING_SECURITY_CONTEXT_KEY, securityContext);

        try {
            saveThroughPublicRepositoryContract(session);

            RedisSessionRepository secondRepository = new RedisSessionRepository(
                    sessionRepository.getSessionRedisOperations());
            secondRepository.setRedisKeyNamespace("daily-language:session:v1");
            secondRepository.setDefaultMaxInactiveInterval(Duration.ofHours(24));

            Session restoredSession = secondRepository.findById(sessionId);
            assertThat(restoredSession).isNotNull();
            assertThat(restoredSession.getMaxInactiveInterval()).isEqualTo(Duration.ofHours(24));

            SecurityContext restoredContext = restoredSession.getAttribute(SPRING_SECURITY_CONTEXT_KEY);
            assertThat(restoredContext.getAuthentication().getPrincipal())
                    .isEqualTo(new UserContext(userId));
            assertThat(restoredContext.getAuthentication().getCredentials()).isNull();

            String redisKey = SESSION_KEY_PREFIX + sessionId;
            assertThat(redisTemplate.hasKey(redisKey)).isTrue();
            assertThat(redisTemplate.opsForHash()
                    .get(redisKey, "sessionAttr:" + SPRING_SECURITY_CONTEXT_KEY))
                    .asString()
                    .contains("UserContext")
                    .contains(userId.toString());
        }
        finally {
            sessionRepository.deleteById(sessionId);
        }
    }

    @Test
    void bindsTheHostedSessionCookieDefaults() {
        Cookie cookie = serverProperties.getServlet().getSession().getCookie();

        assertThat(cookie.getName()).isEqualTo("SESSION");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getMaxAge()).isNull();
        assertThat(cookie.getHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo(Cookie.SameSite.LAX);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void saveThroughPublicRepositoryContract(Session session) {
        SessionRepository repository = sessionRepository;
        repository.save(session);
    }
}
