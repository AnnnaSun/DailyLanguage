package com.dailylanguage.practice.infrastructure;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class PracticeSessionPersistenceIntegrationTests {

    private static final MaterialIdentity CAFE_IDENTITY =
            new MaterialIdentity("en-builtin-cafe-request", "v1");
    private static final String CAFE_SCENARIO = "CAFE_SIMPLE_REQUEST";
    private static final String CAFE_OBJECTIVE =
            "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskRepository learningTaskRepository;

    @Autowired
    private PracticeSessionRepository practiceSessionRepository;

    @Autowired
    private PracticeSessionApplicationService practiceSessionApplicationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // --- repository：schema、ownership 与 durable constraints ---

    @Test
    void insertForOwnedTaskCreatesInProgressSessionWithDatabaseAuthority() {
        OwnedTask owned = createOwnedStartedTask();

        PracticeSession session = practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId());

        assertThat(session.id()).isNotNull();
        assertThat(session.id().version()).isEqualTo(7);
        assertThat(session.taskId()).isEqualTo(owned.taskId());
        assertThat(session.status()).isEqualTo(PracticeSession.Status.IN_PROGRESS);
        assertThat(session.startedAt()).isNotNull();
        assertThat(session.completedAt()).isEmpty();
        assertThat(session.abandonedAt()).isEmpty();
        assertThat(practiceSessionRepository.findOwnedByTask(
                owned.taskId(), owned.ownerId(), owned.profileId())).contains(session);
        assertThat(practiceSessionRepository.findOwned(
                session.id(), owned.ownerId(), owned.profileId())).contains(session);
        assertThat(practiceSessionRepository.findOwnedForUpdate(
                session.id(), owned.ownerId(), owned.profileId())).contains(session);
    }

    @Test
    void insertRequiresTheTaskToBelongToTheCallerAndProfile() {
        OwnedTask owned = createOwnedStartedTask();
        UUID otherUserId = userRepository.create();

        assertThatThrownBy(() -> practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), otherUserId, owned.profileId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("practice session insert requires an owned started learning task");
        assertThatThrownBy(() -> practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(countSessionsForTask(owned.taskId())).isZero();
    }

    @Test
    void insertRequiresTheTaskToBeStarted() {
        // PLANNED Task 不能直接拥有 Session：Session 只能跟随 STARTED transition 创建。
        OwnedTask planned = createOwnedPlannedTask();
        assertThatThrownBy(() -> practiceSessionRepository.insertForOwnedTask(
                planned.taskId(), planned.ownerId(), planned.profileId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("practice session insert requires an owned started learning task");
        assertThat(countSessionsForTask(planned.taskId())).isZero();

        // COMPLETED Task 同样拒绝，防止 terminal Task 再进入练习。
        OwnedTask completed = createOwnedPlannedTask();
        learningTaskRepository.tryStart(completed.taskId(), completed.ownerId(), completed.profileId());
        learningTaskRepository.tryComplete(completed.taskId(), completed.ownerId(), completed.profileId());
        assertThatThrownBy(() -> practiceSessionRepository.insertForOwnedTask(
                completed.taskId(), completed.ownerId(), completed.profileId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(countSessionsForTask(completed.taskId())).isZero();
    }

    @Test
    void hidesSessionFromOtherUserAndOtherLanguageProfile() {
        OwnedTask owned = createOwnedStartedTask();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity otherProfile = languageProfileRepository
                .create(owned.ownerId(), "ja")
                .orElseThrow();
        PracticeSession session = practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId());

        assertThat(practiceSessionRepository.findOwnedByTask(
                owned.taskId(), otherUserId, owned.profileId())).isEmpty();
        assertThat(practiceSessionRepository.findOwnedByTask(
                owned.taskId(), owned.ownerId(), otherProfile.id())).isEmpty();
        assertThat(practiceSessionRepository.findOwned(
                session.id(), otherUserId, owned.profileId())).isEmpty();
        assertThat(practiceSessionRepository.findOwned(
                session.id(), owned.ownerId(), otherProfile.id())).isEmpty();
        assertThat(practiceSessionRepository.findOwnedForUpdate(
                session.id(), otherUserId, owned.profileId())).isEmpty();
    }

    // expected constraint violation 必须在独立 transaction 中执行：共享测试事务被 PostgreSQL
    // abort 后（SQLSTATE 25P02），后续断言只能看到 aborted transaction 而非真实约束行为。
    // NOT_SUPPORTED 下每次 Repository 调用都有自己的事务；验证用 jdbcTemplate 以新事务读取
    // 已提交的 durable state。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void oneSessionPerTaskIsEnforcedByTheDatabase() {
        OwnedTask owned = createOwnedStartedTask();
        practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId());

        // UNIQUE(task_id) 第二层 guard：编程错误以 constraint violation 暴露，不静默返回第二条。
        assertThatThrownBy(() -> practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countSessionsForTask(owned.taskId())).isEqualTo(1);
    }

    @Test
    void responseInsertAcceptsFirstPayloadAndSilentlySkipsConflicts() {
        OwnedTask owned = createOwnedStartedTask();
        PracticeSession session = practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId());

        Optional<java.time.OffsetDateTime> first = practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "order-drink", "  Could I have a medium coffee, please?  ",
                owned.ownerId(), owned.profileId());
        assertThat(first).isPresent();

        // 相同或不同 payload 的重复 insert 都不覆盖、不报错，由调用方比较既有行裁决。
        assertThat(practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "order-drink", "  Could I have a medium coffee, please?  ",
                owned.ownerId(), owned.profileId())).isEmpty();
        assertThat(practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "order-drink", "A large coffee, please.",
                owned.ownerId(), owned.profileId())).isEmpty();

        PracticeSession.LearnerResponse stored = practiceSessionRepository
                .findOwnedResponse(session.id(), "order-drink", owned.ownerId(), owned.profileId())
                .orElseThrow();
        // 首次接受的 exact text 原样保留（含 surrounding whitespace）与首次 submittedAt。
        assertThat(stored.learnerText()).isEqualTo("  Could I have a medium coffee, please?  ");
        assertThat(stored.submittedAt()).isEqualTo(first.orElseThrow());
        assertThat(countResponsesForSession(session.id())).isEqualTo(1);
    }

    @Test
    void responseOperationsAreOwnerScopedAndRequireInProgress() {
        OwnedTask owned = createOwnedStartedTask();
        PracticeSession session = practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId());
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity otherProfile = languageProfileRepository
                .create(owned.ownerId(), "ja")
                .orElseThrow();

        practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "order-drink", "Could I have a medium coffee, please?",
                owned.ownerId(), owned.profileId());

        // foreign user / foreign profile 不能写入也不能读取 private learner text。
        assertThat(practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "ask-price", "How much is it?", otherUserId, owned.profileId()))
                .isEmpty();
        assertThat(practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "ask-price", "How much is it?", owned.ownerId(), otherProfile.id()))
                .isEmpty();
        assertThat(practiceSessionRepository.findOwnedResponse(
                session.id(), "order-drink", otherUserId, owned.profileId())).isEmpty();
        assertThat(practiceSessionRepository.findOwnedResponse(
                session.id(), "order-drink", owned.ownerId(), otherProfile.id())).isEmpty();

        // terminal Session 不再接受新 response。
        jdbcTemplate.update(
                "UPDATE practice_session SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                session.id());
        assertThat(practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "ask-price", "How much is it?", owned.ownerId(), owned.profileId()))
                .isEmpty();

        assertThat(countResponsesForSession(session.id())).isEqualTo(1);
    }

    // 同上：每个 expected CHECK violation 各自运行在独立事务中（各自 abort 互不影响），
    // 最终以新事务验证 durable state 仍为零 response。
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void durableResponseConstraintsRejectInvalidShapes() {
        OwnedTask owned = createOwnedStartedTask();
        PracticeSession session = practiceSessionRepository.insertForOwnedTask(
                owned.taskId(), owned.ownerId(), owned.profileId());

        assertThatThrownBy(() -> practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "order-drink", "", owned.ownerId(), owned.profileId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), "order-drink", "a".repeat(2001), owned.ownerId(), owned.profileId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> practiceSessionRepository.insertOwnedAcceptedResponse(
                session.id(), " order-drink", "Could I have a medium coffee, please?",
                owned.ownerId(), owned.profileId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(countResponsesForSession(session.id())).isZero();
    }

    // --- service：真实 catalog 下的 start / submit 语义 ---

    @Test
    void startCreatesSessionAndStartsTaskAtomicallyThroughTheRealCatalog() {
        OwnedTask owned = createOwnedPlannedTask();

        StartResult result = practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));

        assertThat(result).isInstanceOfSatisfying(StartResult.Created.class, created -> {
            PracticeSession session = created.session();
            assertThat(session.status()).isEqualTo(PracticeSession.Status.IN_PROGRESS);
            assertThat(session.startedAt()).isNotNull();
            assertThat(created.material().materialId()).isEqualTo("en-builtin-cafe-request");
            assertThat(created.material().publishedVersion()).isEqualTo("v1");
            assertThat(created.material().supportLanguage()).isEqualTo("zh-cn");
            assertThat(created.material().steps()).hasSize(3);
            assertThat(created.material().steps().get(0).stepId()).isEqualTo("order-drink");
            assertThat(created.material().steps().get(2).kind()).isEqualTo("SEMANTIC_ONLY");
        });
        assertThat(learningTaskRepository.findOwned(owned.taskId(), owned.ownerId(), owned.profileId()))
                .hasValueSatisfying(task -> assertThat(task.status())
                        .isEqualTo(LearningTask.Status.STARTED));
    }

    @Test
    void repeatedStartReturnsTheSameDurableSession() {
        OwnedTask owned = createOwnedPlannedTask();
        UserContext context = new UserContext(owned.ownerId());

        StartResult.Created first = (StartResult.Created)
                practiceSessionApplicationService.start(owned.profileId(), owned.taskId(), context);

        assertThat(practiceSessionApplicationService.start(owned.profileId(), owned.taskId(), context))
                .isInstanceOfSatisfying(StartResult.Existing.class, existing ->
                        assertThat(existing.session()).isEqualTo(first.session()));
        assertThat(countSessionsForTask(owned.taskId())).isEqualTo(1);
    }

    @Test
    void unavailableOrMismatchedMaterialLeavesZeroMutation() {
        OwnedTask unknownMaterial = createOwnedPlannedTask("en-builtin-not-published", CAFE_SCENARIO);
        OwnedTask mismatchedScenario = createOwnedPlannedTask("en-builtin-cafe-request", "RESTAURANT_ORDER");

        assertThat(practiceSessionApplicationService.start(
                unknownMaterial.profileId(), unknownMaterial.taskId(),
                new UserContext(unknownMaterial.ownerId())))
                .isEqualTo(new StartResult.MaterialUnavailable());
        assertThat(countSessionsForTask(unknownMaterial.taskId())).isZero();
        assertThat(learningTaskRepository.findOwned(
                unknownMaterial.taskId(), unknownMaterial.ownerId(), unknownMaterial.profileId()))
                .hasValueSatisfying(task -> assertThat(task.status())
                        .isEqualTo(LearningTask.Status.PLANNED));

        assertThat(practiceSessionApplicationService.start(
                mismatchedScenario.profileId(), mismatchedScenario.taskId(),
                new UserContext(mismatchedScenario.ownerId())))
                .isEqualTo(new StartResult.MaterialUnavailable());
        assertThat(countSessionsForTask(mismatchedScenario.taskId())).isZero();
    }

    @Test
    void submitsExactAndSemanticResponsesThroughTheRealMaterial() {
        OwnedTask owned = createOwnedPlannedTask();
        StartResult.Created started = (StartResult.Created) practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));
        UUID sessionId = started.session().id();
        UserContext context = new UserContext(owned.ownerId());

        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "order-drink", context,
                "Could I have a medium coffee, please?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "ask-price", context, "How much is it?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "answer-to-go", context,
                "To go, please. Thank you!"))
                .isInstanceOf(SubmitResult.Accepted.class);

        assertThat(countResponsesForSession(sessionId)).isEqualTo(3);
    }

    @Test
    void replayReturnsFirstSubmittedAtAndDifferentPayloadConflicts() {
        OwnedTask owned = createOwnedPlannedTask();
        StartResult.Created started = (StartResult.Created) practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));
        UUID sessionId = started.session().id();
        UserContext context = new UserContext(owned.ownerId());

        SubmitResult.Accepted firstAccepted = (SubmitResult.Accepted) practiceSessionApplicationService
                .submit(owned.profileId(), sessionId, "order-drink", context,
                        "Could I have a medium coffee, please?");
        SubmitResult.Replayed replayed = (SubmitResult.Replayed) practiceSessionApplicationService
                .submit(owned.profileId(), sessionId, "order-drink", context,
                        "Could I have a medium coffee, please?");

        // 重放返回首次持久化产生的 submittedAt 与 exact text，不产生第二行。
        assertThat(replayed.response().submittedAt())
                .isEqualTo(firstAccepted.response().submittedAt());
        assertThat(replayed.response().learnerText())
                .isEqualTo("Could I have a medium coffee, please?");

        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "order-drink", context, "A large coffee, please."))
                .isEqualTo(new SubmitResult.ResponseConflict());
        // 首次 response 不被覆盖。
        assertThat(practiceSessionRepository.findOwnedResponse(
                sessionId, "order-drink", owned.ownerId(), owned.profileId()))
                .hasValueSatisfying(stored ->
                        assertThat(stored.learnerText()).isEqualTo("Could I have a medium coffee, please?"));
        assertThat(countResponsesForSession(sessionId)).isEqualTo(1);
    }

    @Test
    void unknownStepAndForeignSessionAreRejectedWithoutResponses() {
        OwnedTask owned = createOwnedPlannedTask();
        StartResult.Created started = (StartResult.Created) practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));
        UUID sessionId = started.session().id();
        UUID otherUserId = userRepository.create();

        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "unknown-step", new UserContext(owned.ownerId()), "text"))
                .isEqualTo(new SubmitResult.StepNotFound());
        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "order-drink", new UserContext(otherUserId), "text"))
                .isEqualTo(new SubmitResult.SessionNotFound());
        assertThat(countResponsesForSession(sessionId)).isZero();
    }

    @Test
    void terminalSessionRejectsFurtherResponses() {
        OwnedTask owned = createOwnedPlannedTask();
        StartResult.Created started = (StartResult.Created) practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));
        UUID sessionId = started.session().id();

        jdbcTemplate.update(
                "UPDATE practice_session SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP "
                        + "WHERE id = ?",
                sessionId);
        learningTaskRepository.tryComplete(owned.taskId(), owned.ownerId(), owned.profileId());

        assertThat(practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "order-drink", new UserContext(owned.ownerId()), "text"))
                .isEqualTo(new SubmitResult.SessionNotAcceptingResponses());
        assertThat(countResponsesForSession(sessionId)).isZero();
    }

    // --- transaction rollback 与并发裁决：每个 service 调用运行在自己的事务内 ---

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void sessionInsertFailureRollsBackTheTaskTransition() {
        OwnedTask owned = createOwnedPlannedTask();
        // Service 层对可见的既有 Session 会直接返回 Existing，Repository insert gate 也已拒绝
        // PLANNED Task，因此通过 raw SQL 在事务外注入一条冲突 session row（故障注入，绕过 gate），
        // 以 Repository 组合复现 contract 的事务场景：tryStart 成功后 Session insert 失败必须整体回滚。
        jdbcTemplate.update("INSERT INTO practice_session (task_id) VALUES (?)", owned.taskId());

        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            learningTaskRepository.tryStart(owned.taskId(), owned.ownerId(), owned.profileId());
            practiceSessionRepository.insertForOwnedTask(owned.taskId(), owned.ownerId(), owned.profileId());
        })).isInstanceOf(DataIntegrityViolationException.class);

        // Task transition 已随 Session insert 失败回滚，不留下孤立的 STARTED Task。
        assertThat(learningTaskRepository.findOwned(owned.taskId(), owned.ownerId(), owned.profileId()))
                .hasValueSatisfying(task -> assertThat(task.status())
                        .isEqualTo(LearningTask.Status.PLANNED));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentStartProducesExactlyOneSession() throws Exception {
        OwnedTask owned = createOwnedPlannedTask();
        UserContext context = new UserContext(owned.ownerId());

        var outcomes = runConcurrently(2, () -> practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), context));

        long createdCount = outcomes.stream().filter(StartResult.Created.class::isInstance).count();
        long existingCount = outcomes.stream().filter(StartResult.Existing.class::isInstance).count();
        assertThat(createdCount).as("exactly one start wins the transition: %s", outcomes).isEqualTo(1);
        assertThat(existingCount).as("the loser replays the same session: %s", outcomes).isEqualTo(1);

        StartResult.Created created = outcomes.stream()
                .filter(StartResult.Created.class::isInstance).map(StartResult.Created.class::cast)
                .findFirst().orElseThrow();
        StartResult.Existing existing = outcomes.stream()
                .filter(StartResult.Existing.class::isInstance).map(StartResult.Existing.class::cast)
                .findFirst().orElseThrow();
        assertThat(existing.session()).isEqualTo(created.session());
        assertThat(countSessionsForTask(owned.taskId())).isEqualTo(1);
        assertThat(learningTaskRepository.findOwned(owned.taskId(), owned.ownerId(), owned.profileId()))
                .hasValueSatisfying(task -> assertThat(task.status())
                        .isEqualTo(LearningTask.Status.STARTED));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSameResponseAcceptsOnceAndReplaysOnce() throws Exception {
        OwnedTask owned = createOwnedPlannedTask();
        StartResult.Created started = (StartResult.Created) practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));
        UUID sessionId = started.session().id();
        UserContext context = new UserContext(owned.ownerId());
        String learnerText = "Could I have a medium coffee, please?";

        var outcomes = runConcurrently(2, () -> practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "order-drink", context, learnerText));

        long accepted = outcomes.stream().filter(SubmitResult.Accepted.class::isInstance).count();
        long replayed = outcomes.stream().filter(SubmitResult.Replayed.class::isInstance).count();
        assertThat(accepted).as("same payload is accepted exactly once: %s", outcomes).isEqualTo(1);
        assertThat(replayed).as("the other request replays the first submittedAt: %s", outcomes)
                .isEqualTo(1);
        assertThat(countResponsesForSession(sessionId)).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDifferentResponsesAcceptExactlyOne() throws Exception {
        OwnedTask owned = createOwnedPlannedTask();
        StartResult.Created started = (StartResult.Created) practiceSessionApplicationService.start(
                owned.profileId(), owned.taskId(), new UserContext(owned.ownerId()));
        UUID sessionId = started.session().id();
        UserContext context = new UserContext(owned.ownerId());

        var outcomes = runConcurrently(2, index -> practiceSessionApplicationService.submit(
                owned.profileId(), sessionId, "order-drink", context,
                index == 0 ? "Could I have a medium coffee, please?" : "A large coffee, please."));

        long accepted = outcomes.stream().filter(SubmitResult.Accepted.class::isInstance).count();
        long conflicts = outcomes.stream().filter(SubmitResult.ResponseConflict.class::isInstance).count();
        assertThat(accepted).as("exactly one different payload wins: %s", outcomes).isEqualTo(1);
        assertThat(conflicts).as("the other payload conflicts without overwriting: %s", outcomes)
                .isEqualTo(1);
        assertThat(countResponsesForSession(sessionId)).isEqualTo(1);
    }

    // --- helpers ---

    private record OwnedTask(UUID ownerId, UUID profileId, UUID taskId) {
    }

    private OwnedTask createOwnedPlannedTask() {
        return createOwnedPlannedTask("en-builtin-cafe-request", CAFE_SCENARIO);
    }

    /** Repository 层测试的前置条件：Session 只能挂在 STARTED Task 上。 */
    private OwnedTask createOwnedStartedTask() {
        OwnedTask planned = createOwnedPlannedTask();
        learningTaskRepository.tryStart(planned.taskId(), planned.ownerId(), planned.profileId());
        return planned;
    }

    private OwnedTask createOwnedPlannedTask(String materialId, String scenario) {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTaskPlan plan = new LearningTaskPlan(
                profile.id(),
                new MaterialIdentity(materialId, "v1"),
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                scenario,
                CAFE_OBJECTIVE,
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        LearningTask task = learningTaskRepository
                .createOwned(ownerId, plan)
                .orElseThrow();
        return new OwnedTask(ownerId, profile.id(), task.id());
    }

    private int countSessionsForTask(UUID taskId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM practice_session WHERE task_id = ?",
                Integer.class,
                taskId);
        return count == null ? 0 : count;
    }

    private int countResponsesForSession(UUID sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM practice_response WHERE session_id = ?",
                Integer.class,
                sessionId);
        return count == null ? 0 : count;
    }

    private static <T> List<T> runConcurrently(int threads, Callable<T> action) throws Exception {
        return runConcurrently(threads, ignored -> action.call());
    }

    private static <T> List<T> runConcurrently(int threads, ConcurrentAction<T> action)
            throws Exception {
        CountDownLatch startBarrier = new CountDownLatch(1);
        AtomicInteger sequence = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    startBarrier.await();
                    return action.run(sequence.getAndIncrement());
                }));
            }
            startBarrier.countDown();
            List<T> results = new java.util.ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ConcurrentAction<T> {
        T run(int index) throws Exception;
    }
}
