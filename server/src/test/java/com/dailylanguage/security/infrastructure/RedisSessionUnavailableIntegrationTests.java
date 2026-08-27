package com.dailylanguage.security.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",
        "spring.data.redis.lettuce.shutdown-timeout=2s"
})
@EnabledIfEnvironmentVariable(named = "RUN_REDIS_UNAVAILABLE_TESTS", matches = "true")
class RedisSessionUnavailableIntegrationTests {

    @Autowired
    private RedisSessionRepository sessionRepository;

    @Autowired
    private ListableBeanFactory beanFactory;

    @Test
    void failsSessionPersistenceWithoutCreatingAnInMemoryFallback() {
        Map<String, SessionRepository> repositories = beanFactory.getBeansOfType(SessionRepository.class);
        assertThat(repositories).hasSize(1);
        assertThat(repositories.values().iterator().next())
                .isSameAs(sessionRepository)
                .isExactlyInstanceOf(RedisSessionRepository.class);

        Session session = sessionRepository.createSession();

        assertThatThrownBy(() -> saveThroughPublicRepositoryContract(session))
                .isInstanceOf(DataAccessException.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void saveThroughPublicRepositoryContract(Session session) {
        SessionRepository repository = sessionRepository;
        repository.save(session);
    }
}
