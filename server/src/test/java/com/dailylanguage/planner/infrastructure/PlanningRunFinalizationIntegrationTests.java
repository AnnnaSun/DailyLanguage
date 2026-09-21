package com.dailylanguage.planner.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.planner.application.PlanningRunCreationService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.planner.domain.PlanningRun.Status;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

/**
 * 用真实 PostgreSQL durable 数据验证 S9E2A：PlanningRun 的 PENDING → terminal CAS、
 * task linkage 的 owner/profile/planningReason gate、row lock 串行化与 outcome pairing
 * constraints。直接测试 Repository 边界（不含 S9E2B 裁决 Service）；每个用例的数据都在
 * 独立事务中提交，AfterEach 按 FK 依赖顺序清理。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class PlanningRunFinalizationIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private PlanningRunCreationService planningRunCreationService;

    @Autowired
    private PlanningRunRepository planningRunRepository;

    @Autowired
    private LearningTaskRepository learningTaskRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM planning_run_candidate WHERE run_id IN (
                        SELECT id FROM planning_run WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM planning_run WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM model_call_job WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM learning_task WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    @Test
    void finalizesPendingRunAsModelAppliedKeepingWorkflowVersionAndRejectsReplay() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask enrichedTask = enrichedTask(ownerId, profile);

        PlanningRun finalized = planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), enrichedTask.id(), 0L);

        assertThat(finalized.status()).isEqualTo(Status.MODEL_APPLIED);
        assertThat(finalized.learningTaskId()).contains(enrichedTask.id());
        assertThat(finalized.fallbackReason()).isEmpty();
        assertThat(finalized.completedAt()).isPresent();
        // MODEL_APPLIED 保持创建时 workflow version；CAS 递增 row version。
        assertThat(finalized.workflowVersion()).isZero();
        assertThat(finalized.rowVersion()).isEqualTo(1L);
        assertThat(planningRunRepository.findOwned(pending.id(), ownerId, profile.id()))
                .contains(finalized);

        // terminal replay：guard 已不满足，零行 fail closed。
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), enrichedTask.id(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");
        assertThat(planningRunRepository.findOwned(pending.id(), ownerId, profile.id()))
                .contains(finalized);
    }

    @Test
    void finalizesPendingRunAsFallbackAdvancingWorkflowVersionAndRejectsReplay() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask deterministicTask = deterministicTask(ownerId, profile);

        PlanningRun finalized = planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED),
                deterministicTask.id(), 0L);

        assertThat(finalized.status()).isEqualTo(Status.FALLBACK_APPLIED);
        assertThat(finalized.learningTaskId()).contains(deterministicTask.id());
        assertThat(finalized.fallbackReason())
                .contains(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED);
        assertThat(finalized.completedAt()).isPresent();
        // fallback 原子推进 workflow version，使迟到 Model success 不再适用。
        assertThat(finalized.workflowVersion()).isEqualTo(1L);
        assertThat(finalized.rowVersion()).isEqualTo(1L);

        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED),
                deterministicTask.id(), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");
    }

    @Test
    void rejectsTaskWhosePlanningReasonDoesNotMatchTargetStatus() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask deterministicTask = deterministicTask(ownerId, profile);
        LearningTask enrichedTask = enrichedTask(ownerId, profile);

        // MODEL_APPLIED 只接受 MODEL_ENRICHED task；FALLBACK_APPLIED 只接受 deterministic task。
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), deterministicTask.id(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.FALLBACK_APPLIED,
                Optional.of(PlanningRun.FallbackReason.MODEL_OUTPUT_REJECTED),
                enrichedTask.id(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");

        assertThat(planningRunRepository.findOwned(pending.id(), ownerId, profile.id()))
                .contains(pending);
    }

    @Test
    void rejectsWrongOwnerProfileForeignTaskAndStaleRowVersion() {
        UUID ownerId = newUser();
        UUID otherUserId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        LanguageProfileIdentity otherProfile = newProfile(ownerId, "ja");
        LanguageProfileIdentity foreignProfile = newProfile(otherUserId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask foreignTask = enrichedTask(otherUserId, foreignProfile);

        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), otherUserId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), foreignTask.id(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, otherProfile.id(),
                Status.MODEL_APPLIED, Optional.empty(), foreignTask.id(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");
        // 外来 task 即使 reason 正确也因 owner/profile 不一致被 EXISTS gate 拒绝。
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), foreignTask.id(), 0L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");
        // stale row version：CAS guard 拒绝。
        LearningTask enrichedTask = enrichedTask(ownerId, profile);
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), enrichedTask.id(), 99L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planning run finalize transition was not recorded");

        assertThat(planningRunRepository.findOwned(pending.id(), ownerId, profile.id()))
                .contains(pending);
        // wrong owner/profile 对行锁读取不可见。
        assertThat(planningRunRepository.findOwnedForUpdate(
                pending.id(), otherUserId, profile.id())).isEmpty();
        assertThat(planningRunRepository.findOwnedForUpdate(
                pending.id(), ownerId, otherProfile.id())).isEmpty();
    }

    @Test
    void rejectsInvalidFinalizationArguments() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask enrichedTask = enrichedTask(ownerId, profile);

        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.PENDING, Optional.empty(), enrichedTask.id(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("planning run can only finalize to a terminal status");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED,
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED),
                enrichedTask.id(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("model applied planning run cannot have a fallback reason");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.FALLBACK_APPLIED, Optional.empty(), enrichedTask.id(), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fallback applied planning run requires a fallback reason");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), null, 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("learningTaskId must not be null");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                Status.MODEL_APPLIED, Optional.empty(), enrichedTask.id(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expectedRowVersion must not be negative");
        assertThatThrownBy(() -> planningRunRepository.tryFinalizeOwned(
                pending.id(), ownerId, profile.id(),
                null, Optional.empty(), enrichedTask.id(), 0L))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status must not be null");

        assertThat(planningRunRepository.findOwned(pending.id(), ownerId, profile.id()))
                .contains(pending);
    }

    @Test
    void outcomePairingConstraintsFailClosedAtDatabaseLevel() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask deterministicTask = deterministicTask(ownerId, profile);
        UUID runId = pending.id();
        UUID taskId = deterministicTask.id();

        // PENDING 不得携带任何 terminal 事实。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE planning_run SET learning_task_id = ? WHERE id = ?", taskId, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE planning_run SET fallback_reason = 'MODEL_CALL_FAILED' WHERE id = ?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // MODEL_APPLIED 必须绑定 task 与 completedAt，且不得携带 reason。
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE planning_run SET status = 'MODEL_APPLIED' WHERE id = ?", runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE planning_run
                        SET status = 'MODEL_APPLIED', learning_task_id = ?, completed_at = CURRENT_TIMESTAMP,
                            fallback_reason = 'MODEL_CALL_FAILED'
                        WHERE id = ?""", taskId, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // FALLBACK_APPLIED 必须绑定 task、reason 与 completedAt。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE planning_run
                        SET status = 'FALLBACK_APPLIED', learning_task_id = ?, completed_at = CURRENT_TIMESTAMP
                        WHERE id = ?""", taskId, runId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // closed fallback reason 之外的取值被拒绝。
        assertThatThrownBy(() -> jdbcTemplate.update("""
                        UPDATE planning_run
                        SET status = 'FALLBACK_APPLIED', learning_task_id = ?, completed_at = CURRENT_TIMESTAMP,
                            fallback_reason = 'SOMETHING_ELSE', workflow_version = 1
                        WHERE id = ?""", taskId, runId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(planningRunRepository.findOwned(runId, ownerId, profile.id()))
                .contains(pending);
    }

    @Test
    void concurrentFinalizationAllowsExactlyOneTransition() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask enrichedTask = enrichedTask(ownerId, profile);
        LearningTask deterministicTask = deterministicTask(ownerId, profile);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        try {
            Callable<Boolean> modelApplied = () -> {
                start.await();
                try {
                    planningRunRepository.tryFinalizeOwned(
                            pending.id(), ownerId, profile.id(),
                            Status.MODEL_APPLIED, Optional.empty(), enrichedTask.id(), 0L);
                    successes.incrementAndGet();
                    return true;
                } catch (IllegalStateException exception) {
                    return false;
                }
            };
            Callable<Boolean> fallbackApplied = () -> {
                start.await();
                try {
                    planningRunRepository.tryFinalizeOwned(
                            pending.id(), ownerId, profile.id(),
                            Status.FALLBACK_APPLIED,
                            Optional.of(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED),
                            deterministicTask.id(), 0L);
                    successes.incrementAndGet();
                    return true;
                } catch (IllegalStateException exception) {
                    return false;
                }
            };
            Future<Boolean> first = executor.submit(modelApplied);
            Future<Boolean> second = executor.submit(fallbackApplied);
            start.countDown();
            boolean firstWon = first.get(30, TimeUnit.SECONDS);
            boolean secondWon = second.get(30, TimeUnit.SECONDS);

            // 恰一个 transition 成功；数据库只保留 winner 的 task 绑定与 outcome。
            assertThat(firstWon ^ secondWon).isTrue();
            assertThat(successes.get()).isEqualTo(1);
            PlanningRun finalized = planningRunRepository
                    .findOwned(pending.id(), ownerId, profile.id())
                    .orElseThrow();
            assertThat(finalized.rowVersion()).isEqualTo(1L);
            if (firstWon) {
                assertThat(finalized.status()).isEqualTo(Status.MODEL_APPLIED);
                assertThat(finalized.learningTaskId()).contains(enrichedTask.id());
                assertThat(finalized.workflowVersion()).isZero();
            } else {
                assertThat(finalized.status()).isEqualTo(Status.FALLBACK_APPLIED);
                assertThat(finalized.learningTaskId()).contains(deterministicTask.id());
                assertThat(finalized.workflowVersion()).isEqualTo(1L);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rowLockSerializesConcurrentReaderUntilCommittingTransactionReleases() throws Exception {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = newProfile(ownerId, "en");
        PlanningRun pending = pendingRun(ownerId, profile);
        LearningTask enrichedTask = enrichedTask(ownerId, profile);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(transaction -> {
                PlanningRun lockedRun = planningRunRepository
                        .findOwnedForUpdate(pending.id(), ownerId, profile.id())
                        .orElseThrow();
                assertThat(lockedRun.status()).isEqualTo(Status.PENDING);
                locked.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while holding planning run lock", exception);
                }
                planningRunRepository.tryFinalizeOwned(
                        pending.id(), ownerId, profile.id(),
                        Status.MODEL_APPLIED, Optional.empty(), enrichedTask.id(), 0L);
            }));

            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<PlanningRun> reader = executor.submit(() -> transactionTemplate.execute(transaction ->
                    planningRunRepository
                            .findOwnedForUpdate(pending.id(), ownerId, profile.id())
                            .orElseThrow()));
            // 持锁事务未提交前，第二个 FOR UPDATE 读取必须被阻塞。
            Thread.sleep(300);
            assertThat(reader.isDone()).isFalse();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            PlanningRun observed = reader.get(30, TimeUnit.SECONDS);

            // 提交后释放锁：后到者读到 terminal outcome，而不是重复创建。
            assertThat(observed.status()).isEqualTo(Status.MODEL_APPLIED);
            assertThat(observed.learningTaskId()).contains(enrichedTask.id());
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    private LanguageProfileIdentity newProfile(UUID ownerId, String languageCode) {
        return languageProfileRepository.create(ownerId, languageCode).orElseThrow();
    }

    private PlanningRun pendingRun(UUID ownerId, LanguageProfileIdentity profile) {
        return planningRunCreationService
                .create(new UserContext(ownerId), candidateSet(profile.id()),
                        OffsetDateTime.now().plusMinutes(5))
                .run();
    }

    private static PlanningCandidateSet candidateSet(UUID profileId) {
        return new PlanningCandidateSet(List.of(
                deterministicPlan(profileId, 0),
                deterministicPlan(profileId, 1)));
    }

    private LearningTask deterministicTask(UUID ownerId, LanguageProfileIdentity profile) {
        return learningTaskRepository
                .createOwned(ownerId, deterministicPlan(profile.id(), 0))
                .orElseThrow();
    }

    private LearningTask enrichedTask(UUID ownerId, LanguageProfileIdentity profile) {
        return learningTaskRepository
                .createOwned(ownerId, new LearningTaskPlan(
                        profile.id(),
                        identity(0),
                        profile.languageCode(),
                        "zh",
                        MaterialDifficulty.FOUNDATION,
                        7,
                        "Ordering breakfast at a café",
                        "Ask the staff a follow-up question about today's specials",
                        LearningTaskPlan.TaskType.TEXT_PRACTICE,
                        LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                        Optional.of("今天在咖啡馆练习点单，并追问今日特供。")))
                .orElseThrow();
    }

    private static LearningTaskPlan deterministicPlan(UUID profileId, int sequence) {
        return new LearningTaskPlan(
                profileId,
                identity(sequence),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static MaterialIdentity identity(int sequence) {
        return new MaterialIdentity(
                "builtin:text-practice/material-" + sequence, "2026.03.1");
    }
}
