package com.dailylanguage.evaluator.application;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.MaterialUnavailableReason;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextLearningPurpose;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.InconsistentSnapshot;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.MaterialUnavailable;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.NotCompleted;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.NotFound;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepKind;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepOutcome;
import com.dailylanguage.practice.domain.DeterministicAssessment.StepResult;
import com.dailylanguage.practice.domain.DeterministicTextAssessmentPolicy;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.domain.PracticeSession.LearnerResponse;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GroundedEvaluationInputReaderTests {

    private static final String RUBRIC_REFERENCE = "builtin-text-communication-rubric/v1";
    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000061");
    private static final UUID OTHER_USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000062");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000063");
    private static final UUID OTHER_PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000064");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000065");
    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000066");
    private static final UUID OTHER_SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000067");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-05T10:10:30.123Z");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-05T10:15:30.123Z");
    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-09-05T10:18:00.456Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-05T10:22:00.123Z");
    private static final MaterialIdentity CAFE_IDENTITY =
            new MaterialIdentity("en-builtin-cafe-request", "v1");
    private static final MaterialIdentity HISTORICAL_CAFE_IDENTITY =
            new MaterialIdentity("en-builtin-cafe-request", "v0");

    private final PracticeSessionRepository practiceSessionRepository =
            Mockito.mock(PracticeSessionRepository.class);
    private final LearningTaskRepository learningTaskRepository =
            Mockito.mock(LearningTaskRepository.class);
    private final LearningMaterialCatalog materialCatalog =
            Mockito.mock(LearningMaterialCatalog.class);

    private final GroundedEvaluationInputReader reader = new GroundedEvaluationInputReader(
            practiceSessionRepository, learningTaskRepository, materialCatalog);

    @AfterEach
    void verifyNoPersistenceMutation() {
        // 所有分支都只能复用 owner-scoped 读取，不得触发任何 mutation、锁或重选材料查询。
        verify(practiceSessionRepository, never()).insertForOwnedTask(any(), any(), any());
        verify(practiceSessionRepository, never())
                .insertOwnedAcceptedResponse(any(), any(), any(), any(), any());
        verify(practiceSessionRepository, never()).completeOwned(any(), any(), any());
        verify(practiceSessionRepository, never()).insertOwnedAssessment(any(), any(), anyLong(), any(), any());
        verify(practiceSessionRepository, never())
                .insertOwnedStepAssessment(any(), any(), any(), any(), any(), any());
        verify(practiceSessionRepository, never()).findOwnedForUpdate(any(), any(), any());
        verify(learningTaskRepository, never()).createOwned(any(), any());
        verify(learningTaskRepository, never()).tryStart(any(), any(), any());
        verify(learningTaskRepository, never()).tryComplete(any(), any(), any());
        verify(materialCatalog, never()).listAvailable(anyString(), anyString());
    }

    // --- Ready ---

    @Test
    void returnsReadyForConsistentCompletedSnapshot() {
        stubStandardSnapshot();

        GroundedEvaluationInputResult result = reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(Ready.class, ready -> {
            GroundedEvaluationInput input = ready.input();
            assertThat(input.userId()).isEqualTo(USER_ID);
            assertThat(input.languageProfileId()).isEqualTo(PROFILE_ID);
            assertThat(input.task().id()).isEqualTo(TASK_ID);
            assertThat(input.session().id()).isEqualTo(SESSION_ID);
            assertThat(input.assessment().sessionId()).isEqualTo(SESSION_ID);
            assertThat(input.responses()).containsExactlyElementsOf(standardResponses());
            assertThat(input.material().identity()).isEqualTo(CAFE_IDENTITY);
        });
    }

    @Test
    void historicalMaterialResolvesByExactIdentityOnly() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.COMPLETED, "en", USER_ID, PROFILE_ID,
                        HISTORICAL_CAFE_IDENTITY)));
        when(practiceSessionRepository.findOwnedAssessment(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(assessment()));
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(standardResponses());
        when(materialCatalog.findByIdentity(HISTORICAL_CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(materialWithIdentity(HISTORICAL_CAFE_IDENTITY)));

        GroundedEvaluationInputResult result =
                reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID));

        // HISTORICAL_ONLY 版本只要按 Task 保存的完整 identity 可解析即允许读取。
        assertThat(result).isInstanceOf(Ready.class);
        // 只按 exact identity 查询 catalog，绝不重选或读取最新版本。
        verify(materialCatalog).findByIdentity(HISTORICAL_CAFE_IDENTITY, "zh-cn");
        verifyNoMoreInteractions(materialCatalog);
    }

    @Test
    void learnerTextAndResponsesPassThroughUntouched() {
        List<LearnerResponse> responses = List.of(
                new LearnerResponse(SESSION_ID, "order-drink",
                        "  Could I have a medium coffee, please?  ", SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "ask-price", "How much is\tit?", SUBMITTED_AT),
                new LearnerResponse(SESSION_ID, "answer-to-go",
                        " To go, please. Thank you! ", SUBMITTED_AT));
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedTask()));
        when(practiceSessionRepository.findOwnedAssessment(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(assessment()));
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(responses);
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));

        GroundedEvaluationInputResult result = reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID));

        assertThat(result).isInstanceOfSatisfying(Ready.class, ready ->
                // 不 strip、不 normalize、不改写：repository 返回的 response 对象原样进入 input。
                assertThat(ready.input().responses()).containsExactlyElementsOf(responses));
    }

    // --- NotFound：ownership 失败先于一切 private 读取 ---

    @Test
    void unknownWrongOwnerOrWrongProfileSessionsReturnIndistinguishableNotFound() {
        stubStandardSnapshot();
        when(practiceSessionRepository.findOwned(SESSION_ID, OTHER_USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, OTHER_PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(OTHER_USER_ID)))
                .isEqualTo(new NotFound());
        assertThat(reader.readOwned(OTHER_PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new NotFound());
        assertThat(reader.readOwned(PROFILE_ID, UUID.randomUUID(), new UserContext(USER_ID)))
                .isEqualTo(new NotFound());

        // NotFound 在读取任何 private learner text、assessment 或 Task 之前裁决。
        verify(practiceSessionRepository, never()).findOwnedResponses(any(), any(), any());
        verify(practiceSessionRepository, never()).findOwnedAssessment(any(), any(), any());
        verifyNoInteractions(learningTaskRepository, materialCatalog);
    }

    // --- NotCompleted ---

    @Test
    void nonCompletedSessionReturnsNotCompletedWithoutFurtherReads() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(new PracticeSession(
                        SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS,
                        STARTED_AT, Optional.empty(), Optional.empty())));

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new NotCompleted());

        verify(practiceSessionRepository, never()).findOwnedResponses(any(), any(), any());
        verify(practiceSessionRepository, never()).findOwnedAssessment(any(), any(), any());
        verifyNoInteractions(learningTaskRepository, materialCatalog);
    }

    // --- InconsistentSnapshot：durable 结构约束 ---

    @Test
    void missingTaskIsInconsistentSnapshot() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
        // Task 缺失即停止，不继续读取 private data。
        verify(practiceSessionRepository, never()).findOwnedResponses(any(), any(), any());
        verifyNoInteractions(materialCatalog);
    }

    @Test
    void taskBoundToDifferentCallerOrProfileIsInconsistentSnapshot() {
        stubStandardSnapshot();
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.COMPLETED, "en", OTHER_USER_ID,
                        PROFILE_ID, CAFE_IDENTITY)));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());

        Mockito.reset(learningTaskRepository, practiceSessionRepository, materialCatalog);
        stubStandardSnapshot();
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.COMPLETED, "en", USER_ID,
                        OTHER_PROFILE_ID, CAFE_IDENTITY)));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    @Test
    void nonCompletedTaskForCompletedSessionIsInconsistentSnapshot() {
        stubStandardSnapshot();
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED, "en", USER_ID, PROFILE_ID,
                        CAFE_IDENTITY)));

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    @Test
    void missingAssessmentIsInconsistentSnapshot() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedTask()));
        when(practiceSessionRepository.findOwnedAssessment(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    @Test
    void assessmentForOtherSessionIsInconsistentSnapshot() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedTask()));
        when(practiceSessionRepository.findOwnedAssessment(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(new DeterministicAssessment(
                        OTHER_SESSION_ID,
                        DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                        390L,
                        COMPLETED_AT,
                        assessment().stepResults())));
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(standardResponses());
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    @Test
    void duplicateMaterialStepIdsAreInconsistentSnapshot() {
        stubSessionTaskAssessmentAndResponses();
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(material(List.of(
                        step("order-drink", TextStepKind.EXACT),
                        step("ask-price", TextStepKind.EXACT),
                        step("order-drink", TextStepKind.SEMANTIC_ONLY)))));

        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    @Test
    void missingExtraDuplicateOrCrossSessionResponsesAreInconsistentSnapshot() {
        stubSessionTaskAssessmentAndResponses();
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));

        // 缺失：material 三个 step 只读到两个 response。
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(responses("order-drink", "ask-price"));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());

        // 额外：包含 material 未定义的 step。
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(List.of(
                        response("order-drink"), response("ask-price"), response("answer-to-go"),
                        new LearnerResponse(SESSION_ID, "extra-step", "surplus", SUBMITTED_AT)));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());

        // 重复：同一 step 出现两条 response。
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(List.of(
                        response("order-drink"), response("order-drink"),
                        response("ask-price"), response("answer-to-go")));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());

        // 跨 Session：response 属于另一个 Session。
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(List.of(
                        response("order-drink"), response("ask-price"),
                        new LearnerResponse(OTHER_SESSION_ID, "answer-to-go", "foreign", SUBMITTED_AT)));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    // --- MaterialUnavailable：exact identity 解析失败 ---

    @Test
    void unavailableMaterialReturnsMaterialUnavailable() {
        stubSessionTaskAssessmentAndResponses();
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(new MaterialQueryResult.Unavailable(MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new MaterialUnavailable());

        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(new MaterialQueryResult.Unavailable(
                        MaterialUnavailableReason.SUPPORT_LANGUAGE_NOT_PUBLISHED));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new MaterialUnavailable());
    }

    @Test
    void materialIdentityOrTargetLanguageMismatchIsInconsistentSnapshot() {
        stubSessionTaskAssessmentAndResponses();
        // catalog 返回的 material 不持有 Task 保存的 exact identity。
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(materialWithIdentity(
                        new MaterialIdentity("en-builtin-cafe-request", "v9"))));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());

        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(material("ja")));
        assertThat(reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isEqualTo(new InconsistentSnapshot());
    }

    // --- 基础设施异常不吞成业务失败 ---

    @Test
    void sessionReadInfrastructureExceptionPropagates() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database unavailable");
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID)).thenThrow(failure);

        assertThatThrownBy(() -> reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isSameAs(failure);
    }

    @Test
    void responseReadAndCatalogInfrastructureExceptionsPropagate() {
        DataAccessResourceFailureException responseFailure =
                new DataAccessResourceFailureException("database unavailable");
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedTask()));
        when(practiceSessionRepository.findOwnedAssessment(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(assessment()));
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenThrow(responseFailure);
        assertThatThrownBy(() -> reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isSameAs(responseFailure);

        Mockito.reset(practiceSessionRepository, learningTaskRepository, materialCatalog);
        DataAccessResourceFailureException catalogFailure =
                new DataAccessResourceFailureException("catalog unavailable");
        stubSessionTaskAssessmentAndResponses();
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn")).thenThrow(catalogFailure);
        assertThatThrownBy(() -> reader.readOwned(PROFILE_ID, SESSION_ID, new UserContext(USER_ID)))
                .isSameAs(catalogFailure);
    }

    // --- 编程错误与事务契约 ---

    @Test
    void nullArgumentsAreProgrammingErrorsWithoutInputContent() {
        assertThatThrownBy(() -> reader.readOwned(null, SESSION_ID, new UserContext(USER_ID)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("languageProfileId must not be null");
        assertThatThrownBy(() -> reader.readOwned(PROFILE_ID, null, new UserContext(USER_ID)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sessionId must not be null");
        assertThatThrownBy(() -> reader.readOwned(PROFILE_ID, SESSION_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
    }

    @Test
    void readOwnedDeclaresShortReadOnlyTransaction()
            throws NoSuchMethodException {
        Transactional transactional = GroundedEvaluationInputReader.class
                .getMethod("readOwned", UUID.class, UUID.class, UserContext.class)
                .getAnnotation(Transactional.class);

        // 短 readOnly transaction 必须声明在 bean 入口上，调用方不能依赖 self-invocation 获得事务。
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    // --- stubs 与 fixtures ---

    private void stubStandardSnapshot() {
        stubSessionTaskAssessmentAndResponses();
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
    }

    private void stubSessionTaskAssessmentAndResponses() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedTask()));
        when(practiceSessionRepository.findOwnedAssessment(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(assessment()));
        when(practiceSessionRepository.findOwnedResponses(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(standardResponses());
    }

    private static LearningTask completedTask() {
        return task(LearningTask.Status.COMPLETED, "en", USER_ID, PROFILE_ID, CAFE_IDENTITY);
    }

    private static LearningTask task(
            LearningTask.Status status, String targetLanguage, UUID userId, UUID profileId,
            MaterialIdentity identity) {
        return new LearningTask(
                TASK_ID,
                userId,
                profileId,
                identity,
                targetLanguage,
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                status,
                CREATED_AT,
                Optional.of(STARTED_AT),
                status == LearningTask.Status.COMPLETED ? Optional.of(COMPLETED_AT) : Optional.empty());
    }

    private static PracticeSession completedSession() {
        return new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(COMPLETED_AT), Optional.empty());
    }

    private static DeterministicAssessment assessment() {
        return new DeterministicAssessment(
                SESSION_ID,
                DeterministicTextAssessmentPolicy.ASSESSMENT_POLICY_VERSION,
                390L,
                COMPLETED_AT,
                List.of(
                        new StepResult("order-drink", StepKind.EXACT, StepOutcome.MATCHED),
                        new StepResult("ask-price", StepKind.EXACT, StepOutcome.MATCHED),
                        new StepResult("answer-to-go", StepKind.SEMANTIC_ONLY, StepOutcome.NOT_APPLICABLE)));
    }

    private static List<LearnerResponse> standardResponses() {
        return List.of(
                response("order-drink"), response("ask-price"), response("answer-to-go"));
    }

    private static List<LearnerResponse> responses(String... stepIds) {
        return Arrays.stream(stepIds).map(GroundedEvaluationInputReaderTests::response).toList();
    }

    private static LearnerResponse response(String stepId) {
        return new LearnerResponse(SESSION_ID, stepId, "learner text for " + stepId, SUBMITTED_AT);
    }

    private static PublishedLearningMaterial cafeMaterial() {
        return material("en");
    }

    private static PublishedLearningMaterial materialWithIdentity(MaterialIdentity identity) {
        return new PublishedLearningMaterial(
                identity,
                core("en", standardSteps()),
                scaffolds(),
                lineage());
    }

    private static PublishedLearningMaterial material(String targetLanguage) {
        return new PublishedLearningMaterial(
                CAFE_IDENTITY, core(targetLanguage, standardSteps()), scaffolds(), lineage());
    }

    private static PublishedLearningMaterial material(List<TextPracticeStep> steps) {
        return new PublishedLearningMaterial(CAFE_IDENTITY, core("en", steps), scaffolds(), lineage());
    }

    private static TargetPracticeCore core(String targetLanguage, List<TextPracticeStep> steps) {
        return new TargetPracticeCore(
                targetLanguage,
                MaterialDifficulty.FOUNDATION,
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                "You are at a coffee shop.",
                null,
                steps,
                RUBRIC_REFERENCE);
    }

    private static List<TextPracticeStep> standardSteps() {
        return List.of(
                step("order-drink", TextStepKind.EXACT),
                step("ask-price", TextStepKind.EXACT),
                step("answer-to-go", TextStepKind.SEMANTIC_ONLY));
    }

    private static TextPracticeStep step(String stepId, TextStepKind kind) {
        return new TextPracticeStep(
                stepId, kind, TextLearningPurpose.PRACTICE, "Prompt for " + stepId,
                kind == TextStepKind.EXACT ? List.of("accepted answer") : List.of());
    }

    private static MaterialQueryResult available(PublishedLearningMaterial material) {
        return new MaterialQueryResult.Available(material, material.supportScaffolds().getFirst());
    }

    private static List<SupportScaffold> scaffolds() {
        return List.of(new SupportScaffold("zh-cn", "instruction", "explanation", "hint", null, List.of()));
    }

    private static MaterialSourceLineage lineage() {
        return new MaterialSourceLineage("PROJECT_ORIGINAL", "1", "AGPL-3.0",
                "sha256:" + "0".repeat(64));
    }
}
