package com.dailylanguage.identity.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = IdentityBoundaryHttpTests.TestApplication.class, properties = {
        "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms"
})
@EnabledIfEnvironmentVariable(named = "IDENTITY_REDIS_UNAVAILABLE_TESTS", matches = "true")
class RedisSessionUnavailableIntegrationTests {

    @Autowired
    private RedisSessionRepository sessions;
    @Autowired
    private ApplicationContext application;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void refusesPersistenceInsteadOfFallingBackToAnInMemoryRepository() {
        assertThat(application.getBeansOfType(SessionRepository.class)).hasSize(1).containsValue(sessions);
        Session session = sessions.createSession();

        assertThatThrownBy(() -> ((SessionRepository) sessions).save(session))
                .isInstanceOf(DataAccessException.class);
    }
}
