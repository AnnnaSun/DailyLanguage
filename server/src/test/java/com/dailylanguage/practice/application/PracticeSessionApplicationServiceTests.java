package com.dailylanguage.practice.application;

import java.lang.reflect.RecordComponent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.MaterialQueryResult.Available;
import com.dailylanguage.content.domain.MaterialSourceLineage;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;
import com.dailylanguage.content.domain.TextPracticeStep;
import com.dailylanguage.content.domain.TextStepKind;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.PracticeMaterialView;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PracticeSessionApplicationServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000041");
    private static final UserContext USER_CONTEXT = new UserContext(USER_ID);
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000042");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000043");
    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000044");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-04T10:15:30.123Z");
    private static final OffsetDateTime SUBMITTED_AT = OffsetDateTime.parse("2026-09-04T10:18:00.456Z");
    private static final MaterialIdentity CAFE_IDENTITY =
            new MaterialIdentity("en-builtin-cafe-request", "v1");

    private final LearningTaskRepository learningTaskRepository =
            Mockito.mock(LearningTaskRepository.class);
    private final PracticeSessionRepository practiceSessionRepository =
            Mockito.mock(PracticeSessionRepository.class);
    private final LearningMaterialCatalog materialCatalog =
            Mockito.mock(LearningMaterialCatalog.class);

    private final PracticeSessionApplicationService service = new PracticeSessionApplicationService(
            learningTaskRepository, practiceSessionRepository, materialCatalog);

    // --- start ---

    @Test
    void createsSessionForOwnedPlannedTaskFollowingTheContractedFlow() {
        LearningTask plannedTask = task(LearningTask.Status.PLANNED);
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(plannedTask));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        LearningTask startedTask = task(LearningTask.Status.STARTED);
        when(learningTaskRepository.tryStart(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(startedTask));
        PracticeSession createdSession = inProgressSession();
        when(practiceSessionRepository.insertForOwnedTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(createdSession);

        StartResult result = service.start(PROFILE_ID, TASK_ID, USER_CONTEXT);

        assertThat(result).isEqualTo(new StartResult.Created(createdSession, cafeMaterialView()));
        // Contract flow：owned Task → exact material → existing Session → tryStart → insert。
        InOrder flow = inOrder(learningTaskRepository, materialCatalog, practiceSessionRepository);
        flow.verify(learningTaskRepository).findOwned(TASK_ID, USER_ID, PROFILE_ID);
        flow.verify(materialCatalog).findByIdentity(CAFE_IDENTITY, "zh-cn");
        flow.verify(practiceSessionRepository).findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID);
        flow.verify(learningTaskRepository).tryStart(TASK_ID, USER_ID, PROFILE_ID);
        flow.verify(practiceSessionRepository).insertForOwnedTask(TASK_ID, USER_ID, PROFILE_ID);
    }

    @Test
    void returnsExistingSessionForRepeatedStartWithoutSecondInsert() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        PracticeSession existingSession = inProgressSession();
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(existingSession));

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.Existing(existingSession, cafeMaterialView()));

        verify(learningTaskRepository, never()).tryStart(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(practiceSessionRepository, never())
                .insertForOwnedTask(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void unknownOrForeignTaskIsRejectedBeforeMaterialResolutionAndAnyMutation() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.TaskNotFound());

        verifyNoInteractions(materialCatalog, practiceSessionRepository);
        verify(learningTaskRepository, never()).tryStart(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void unavailableExactMaterialLeavesZeroMutation() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.PLANNED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(new MaterialQueryResult.Unavailable(
                        com.dailylanguage.content.domain.MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.MaterialUnavailable());

        verifyNoInteractions(practiceSessionRepository);
        verify(learningTaskRepository, never()).tryStart(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void materialSnapshotMismatchWithTaskFailsClosed() {
        LearningTask plannedTask = task(LearningTask.Status.PLANNED);
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(plannedTask));

        // resolved identity 与 Task 锁定 identity 不一致。
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial(new MaterialIdentity("en-builtin-other", "v1"))));
        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.MaterialUnavailable());

        // scaffold support language 与 Task support language 不一致。
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterialWithScaffoldLanguage("ja")));
        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.MaterialUnavailable());

        // scenario / communication objective 与 Task snapshot 不一致。
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterialWithScenario("RESTAURANT_ORDER")));
        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.MaterialUnavailable());

        verifyNoInteractions(practiceSessionRepository);
        verify(learningTaskRepository, never()).tryStart(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void startedTaskWithoutSessionIsRejectedWithoutMutation() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.TaskNotStartable());

        verify(learningTaskRepository, never()).tryStart(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(practiceSessionRepository, never())
                .insertForOwnedTask(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void completedTaskIsRejectedWithoutMutation() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.COMPLETED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.TaskNotStartable());

        verify(learningTaskRepository, never()).tryStart(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void sessionInsertFailurePropagatesInsteadOfReturningABusinessResult() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.PLANNED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(learningTaskRepository.tryStart(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(practiceSessionRepository.insertForOwnedTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenThrow(new IllegalStateException("session insert failed"));

        // unexpected failure 以异常结束事务（由 Spring 回滚 Task transition），
        // 不允许捕获后返回 typed result 并提交孤立的 STARTED Task。
        assertThatThrownBy(() -> service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("session insert failed");
    }

    @Test
    void concurrentStartLoserReturnsTheWinnerSession() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.PLANNED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        PracticeSession winnerSession = inProgressSession();
        // 首次 read 在获胜方 commit 前为空；tryStart 失败后的 owner-scoped re-read 命中获胜方 Session。
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerSession));
        when(learningTaskRepository.tryStart(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.Existing(winnerSession, cafeMaterialView()));

        verify(practiceSessionRepository, never())
                .insertForOwnedTask(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void concurrentStartLoserWithoutAnySessionIsRejected() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.PLANNED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(learningTaskRepository.tryStart(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.start(PROFILE_ID, TASK_ID, USER_CONTEXT))
                .isEqualTo(new StartResult.TaskNotStartable());

        verify(practiceSessionRepository, never())
                .insertForOwnedTask(any(UUID.class), any(UUID.class), any(UUID.class));
    }

    @Test
    void materialViewIsASafeProjectionWithoutAnswersRubricOrOwnership() {
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.PLANNED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedByTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(learningTaskRepository.tryStart(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(practiceSessionRepository.insertForOwnedTask(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(inProgressSession());

        StartResult.Created created =
                (StartResult.Created) service.start(PROFILE_ID, TASK_ID, USER_CONTEXT);

        PracticeMaterialView view = created.material();
        List<String> viewComponents = componentNames(PracticeMaterialView.class);
        assertThat(viewComponents).doesNotContain(
                "acceptedAnswers", "semanticRubricReference", "sourceLineage", "userId");
        List<String> stepComponents = componentNames(PracticeMaterialView.StepView.class);
        assertThat(stepComponents).containsExactly("stepId", "kind", "prompt");

        assertThat(view.materialId()).isEqualTo("en-builtin-cafe-request");
        assertThat(view.publishedVersion()).isEqualTo("v1");
        assertThat(view.targetLanguage()).isEqualTo("en");
        assertThat(view.supportLanguage()).isEqualTo("zh-cn");
        assertThat(view.scenario()).isEqualTo("CAFE_SIMPLE_REQUEST");
        assertThat(view.instruction()).isEqualTo("完成点单的中文指令");
        assertThat(view.steps()).containsExactly(
                new PracticeMaterialView.StepView("order-drink", "EXACT", "Order a medium coffee politely."),
                new PracticeMaterialView.StepView("answer-to-go", "SEMANTIC_ONLY", "Answer to go."));
    }

    // --- submit ---

    @Test
    void acceptsExactStepResponseWithExactTextPreserved() {
        String rawLearnerText = "  Could I have a medium coffee,\tplease?  ";
        arrangeSubmittableSession();

        SubmitResult result = service.submit(
                PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, rawLearnerText);

        assertThat(result).isEqualTo(new SubmitResult.Accepted(
                new PracticeSession.LearnerResponse(SESSION_ID, "order-drink", rawLearnerText, SUBMITTED_AT)));
        // 传入数据库的是原始文本：不 trim、不改大小写、不做 normalization；owner scope 随调用显式传入。
        verify(practiceSessionRepository).insertOwnedAcceptedResponse(
                SESSION_ID, "order-drink", rawLearnerText, USER_ID, PROFILE_ID);
        // Contract flow：owned Session → owned Task → material → stepId → lock → 再确认 → insert。
        InOrder flow = inOrder(practiceSessionRepository, learningTaskRepository, materialCatalog);
        flow.verify(practiceSessionRepository).findOwned(SESSION_ID, USER_ID, PROFILE_ID);
        flow.verify(learningTaskRepository).findOwned(TASK_ID, USER_ID, PROFILE_ID);
        flow.verify(materialCatalog).findByIdentity(CAFE_IDENTITY, "zh-cn");
        flow.verify(practiceSessionRepository).findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID);
        flow.verify(practiceSessionRepository).insertOwnedAcceptedResponse(
                SESSION_ID, "order-drink", rawLearnerText, USER_ID, PROFILE_ID);
    }

    @Test
    void acceptsSemanticOnlyStepResponse() {
        arrangeSubmittableSession();

        assertThat(service.submit(
                PROFILE_ID, SESSION_ID, "answer-to-go", USER_CONTEXT, "To go, please. Thanks!"))
                .isEqualTo(new SubmitResult.Accepted(new PracticeSession.LearnerResponse(
                        SESSION_ID, "answer-to-go", "To go, please. Thanks!", SUBMITTED_AT)));
    }

    @Test
    void invalidLearnerTextIsRejectedBeforeAnyRepositoryAccess() {
        String oversized = "a".repeat(PracticeSession.LearnerResponse.MAXIMUM_LEARNER_TEXT_CODE_POINTS + 1);

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, null))
                .isEqualTo(new SubmitResult.InvalidResponse());
        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "  \t "))
                .isEqualTo(new SubmitResult.InvalidResponse());
        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, oversized))
                .isEqualTo(new SubmitResult.InvalidResponse());

        verifyNoInteractions(practiceSessionRepository, learningTaskRepository, materialCatalog);
    }

    @Test
    void unknownOrForeignSessionIsNotFound() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "To go please"))
                .isEqualTo(new SubmitResult.SessionNotFound());

        verifyNoInteractions(learningTaskRepository, materialCatalog);
        verify(practiceSessionRepository, never())
                .insertOwnedAcceptedResponse(any(), any(), any(), any(), any());
    }

    @Test
    void unknownStepIsRejectedBeforeLockingOrInserting() {
        arrangeSubmittableSession();

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "unknown-step", USER_CONTEXT, "To go please"))
                .isEqualTo(new SubmitResult.StepNotFound());

        verify(practiceSessionRepository, never()).findOwnedForUpdate(any(UUID.class), any(UUID.class), any(UUID.class));
        verify(practiceSessionRepository, never())
                .insertOwnedAcceptedResponse(any(), any(), any(), any(), any());
    }

    @Test
    void materialUnavailableOrInconsistentFailsClosedWithoutInsert() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(inProgressSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(new MaterialQueryResult.Unavailable(
                        com.dailylanguage.content.domain.MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED));

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "To go please"))
                .isEqualTo(new SubmitResult.MaterialUnavailable());

        verify(practiceSessionRepository, never())
                .insertOwnedAcceptedResponse(any(), any(), any(), any(), any());
    }

    @Test
    void missingTaskForOwnedSessionFailsClosed() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(inProgressSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "To go please"))
                .isEqualTo(new SubmitResult.MaterialUnavailable());

        verifyNoInteractions(materialCatalog);
    }

    @Test
    void sessionThatIsNotInProgressAfterLockingRejectsTheResponse() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(inProgressSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        PracticeSession completedSession = new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(STARTED_AT.plusMinutes(5)), Optional.empty());
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(completedSession));

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "To go please"))
                .isEqualTo(new SubmitResult.SessionNotAcceptingResponses());

        verify(practiceSessionRepository, never())
                .insertOwnedAcceptedResponse(any(), any(), any(), any(), any());
    }

    @Test
    void samePayloadReplayReturnsTheFirstSubmittedAtWithoutMutation() {
        arrangeSubmittableSession();
        when(practiceSessionRepository.insertOwnedAcceptedResponse(
                SESSION_ID, "order-drink", "Same text", USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        PracticeSession.LearnerResponse stored = new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", "Same text", SUBMITTED_AT);
        when(practiceSessionRepository.findOwnedResponse(SESSION_ID, "order-drink", USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(stored));

        assertThat(service.submit(PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "Same text"))
                .isEqualTo(new SubmitResult.Replayed(stored));
    }

    @Test
    void differentPayloadConflictKeepsTheFirstAcceptedResponse() {
        arrangeSubmittableSession();
        when(practiceSessionRepository.insertOwnedAcceptedResponse(
                SESSION_ID, "order-drink", "Could I have a large coffee, please?", USER_ID, PROFILE_ID))
                .thenReturn(Optional.empty());
        when(practiceSessionRepository.findOwnedResponse(SESSION_ID, "order-drink", USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(new PracticeSession.LearnerResponse(
                        SESSION_ID, "order-drink", "Could I have a medium coffee, please?", SUBMITTED_AT)));

        assertThat(service.submit(
                PROFILE_ID, SESSION_ID, "order-drink", USER_CONTEXT, "Could I have a large coffee, please?"))
                .isEqualTo(new SubmitResult.ResponseConflict());
    }

    // --- fixtures ---

    private void arrangeSubmittableSession() {
        when(practiceSessionRepository.findOwned(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(inProgressSession()));
        when(learningTaskRepository.findOwned(TASK_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(task(LearningTask.Status.STARTED)));
        when(materialCatalog.findByIdentity(CAFE_IDENTITY, "zh-cn"))
                .thenReturn(available(cafeMaterial()));
        when(practiceSessionRepository.findOwnedForUpdate(SESSION_ID, USER_ID, PROFILE_ID))
                .thenReturn(Optional.of(inProgressSession()));
        when(practiceSessionRepository.insertOwnedAcceptedResponse(
                eq(SESSION_ID), eq("order-drink"), any(), eq(USER_ID), eq(PROFILE_ID)))
                .thenReturn(Optional.of(SUBMITTED_AT));
        when(practiceSessionRepository.insertOwnedAcceptedResponse(
                eq(SESSION_ID), eq("answer-to-go"), any(), eq(USER_ID), eq(PROFILE_ID)))
                .thenReturn(Optional.of(SUBMITTED_AT));
    }

    private static List<String> componentNames(Class<? extends Record> recordType) {
        return Stream.of(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    private static LearningTask task(LearningTask.Status status) {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-04T10:10:00.000Z");
        return new LearningTask(
                TASK_ID,
                USER_ID,
                PROFILE_ID,
                CAFE_IDENTITY,
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                status,
                createdAt,
                status == LearningTask.Status.PLANNED ? Optional.empty() : Optional.of(STARTED_AT),
                status == LearningTask.Status.COMPLETED
                        ? Optional.of(STARTED_AT.plusMinutes(5))
                        : Optional.empty());
    }

    private static PracticeSession inProgressSession() {
        return new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                Optional.empty(), Optional.empty());
    }

    private static Available available(PublishedLearningMaterial material) {
        return new Available(material, material.supportScaffolds().get(0));
    }

    private static PublishedLearningMaterial cafeMaterial() {
        return cafeMaterial(CAFE_IDENTITY);
    }

    private static PublishedLearningMaterial cafeMaterial(MaterialIdentity identity) {
        TargetPracticeCore core = new TargetPracticeCore(
                "en",
                MaterialDifficulty.FOUNDATION,
                "CAFE_SIMPLE_REQUEST",
                "Make a polite request, ask about price, and answer a follow-up question in a coffee shop.",
                "You are at a coffee shop. The barista asks: \"What can I get for you?\"",
                null,
                List.of(
                        new TextPracticeStep(
                                "order-drink", TextStepKind.EXACT, "Order a medium coffee politely.",
                                List.of("Could I have a medium coffee, please?")),
                        new TextPracticeStep(
                                "answer-to-go", TextStepKind.SEMANTIC_ONLY, "Answer to go.", List.of())),
                "builtin-text-communication-rubric/v1");
        return new PublishedLearningMaterial(
                identity,
                core,
                List.of(new SupportScaffold(
                        "zh-cn", "完成点单的中文指令", "场景解释", "提示", "对比提示")),
                new MaterialSourceLineage("PROJECT_ORIGINAL", "v1", "AGPL-3.0", "sha256"));
    }

    private static PublishedLearningMaterial cafeMaterialWithScaffoldLanguage(String supportLanguage) {
        PublishedLearningMaterial material = cafeMaterial();
        return new PublishedLearningMaterial(
                material.identity(),
                material.targetCore(),
                List.of(new SupportScaffold(
                        supportLanguage, "完成点单的中文指令", "场景解释", "提示", "对比提示")),
                material.sourceLineage());
    }

    private static PublishedLearningMaterial cafeMaterialWithScenario(String scenario) {
        PublishedLearningMaterial material = cafeMaterial();
        TargetPracticeCore original = material.targetCore();
        TargetPracticeCore changed = new TargetPracticeCore(
                original.targetLanguage(), original.difficulty(), scenario,
                original.communicationObjective(), original.targetLanguageText(),
                original.readingInfo(), original.steps(), original.semanticRubricReference());
        return new PublishedLearningMaterial(
                material.identity(), changed, material.supportScaffolds(), material.sourceLineage());
    }

    private static PracticeMaterialView cafeMaterialView() {
        return PracticeMaterialView.from(available(cafeMaterial()));
    }
}
