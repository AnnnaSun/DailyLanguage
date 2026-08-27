package com.dailylanguage.user.infrastructure;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class SingleUserPersistenceIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsOnceAndThenReusesThePersistentSingleUser() {
        jdbcTemplate.update("UPDATE single_user_instance SET user_id = NULL WHERE singleton_key = TRUE");
        long userCountBeforeBootstrap = appUserCount();

        UUID createdUserId = userRepository.getOrCreateSingleUser();
        UUID reusedUserId = userRepository.getOrCreateSingleUser();

        assertThat(createdUserId.version()).isEqualTo(7);
        assertThat(reusedUserId).isEqualTo(createdUserId);
        assertThat(appUserCount()).isEqualTo(userCountBeforeBootstrap + 1);
        assertThat(singleUserId()).isEqualTo(createdUserId);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentBootstrapCreatesOnlyOnePersistentSingleUser() throws Exception {
        assumeTrue(assignedSingleUserIds().isEmpty(),
                "Concurrent bootstrap test requires an uninitialized single-user slot");
        long userCountBeforeBootstrap = appUserCount();
        int callerCount = 4;
        CountDownLatch callersReady = new CountDownLatch(callerCount);
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callerCount);

        try {
            List<Future<UUID>> results = IntStream.range(0, callerCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        callersReady.countDown();
                        startTogether.await();
                        return userRepository.getOrCreateSingleUser();
                    }))
                    .toList();
            assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
            startTogether.countDown();

            Set<UUID> returnedUserIds = new HashSet<>();
            for (Future<UUID> result : results) {
                returnedUserIds.add(result.get(10, TimeUnit.SECONDS));
            }

            assertThat(returnedUserIds).hasSize(1);
            assertThat(assignedSingleUserIds()).containsExactly(returnedUserIds.iterator().next());
            assertThat(appUserCount()).isEqualTo(userCountBeforeBootstrap + 1);
        }
        finally {
            executor.shutdownNow();
            assignedSingleUserIds().forEach(this::deleteTestSingleUser);
        }
    }

    private long appUserCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
    }

    private UUID singleUserId() {
        return jdbcTemplate.queryForObject(
                "SELECT user_id FROM single_user_instance WHERE singleton_key = TRUE",
                UUID.class);
    }

    private List<UUID> assignedSingleUserIds() {
        return jdbcTemplate.query(
                "SELECT user_id FROM single_user_instance WHERE user_id IS NOT NULL",
                (resultSet, rowNumber) -> resultSet.getObject("user_id", UUID.class));
    }

    private void deleteTestSingleUser(UUID userId) {
        jdbcTemplate.update(
                "UPDATE single_user_instance SET user_id = NULL WHERE user_id = ?",
                userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }
}
