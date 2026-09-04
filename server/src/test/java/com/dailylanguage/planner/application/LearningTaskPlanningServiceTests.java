package com.dailylanguage.planner.application;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.application.LanguageProfileAccessService;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidRequest;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.LanguageProfileNotFound;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Unavailable;
import com.dailylanguage.security.domain.UserContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LearningTaskPlanningServiceTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000011");
    private static final UserContext USER_CONTEXT = new UserContext(USER_ID);
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000012");
    private static final LanguageProfileIdentity ENGLISH_PROFILE =
            new LanguageProfileIdentity(PROFILE_ID, USER_ID, "en");

    private final LanguageProfileAccessService languageProfileAccessService =
            Mockito.mock(LanguageProfileAccessService.class);
    private final LearningTaskPlanner learningTaskPlanner = Mockito.mock(LearningTaskPlanner.class);
    private final LearningTaskRepository learningTaskRepository =
            Mockito.mock(LearningTaskRepository.class);

    private final LearningTaskPlanningService service =
            new LearningTaskPlanningService(languageProfileAccessService, learningTaskPlanner, learningTaskRepository);

    @BeforeEach
    void ownedProfileIsVisibleToTheAuthenticatedUser() {
        when(languageProfileAccessService.findProfileOwnedByUser(PROFILE_ID, USER_CONTEXT))
                .thenReturn(Optional.of(ENGLISH_PROFILE));
    }

    @Test
    void createsDurableOwnedTaskWithNormalizedRequestForOwnedProfile() {
        LearningTaskPlan plan = plan();
        LearningTask durableTask = durableTask();
        when(learningTaskPlanner.plan(any(PlanningRequest.class))).thenReturn(new PlanningResult.Planned(plan));
        when(learningTaskRepository.createOwned(USER_ID, plan)).thenReturn(Optional.of(durableTask));

        LearningTaskPlanningResult result = service.plan(
                PROFILE_ID, USER_CONTEXT, command(" ZH-CN ", "FOUNDATION", 10));

        assertThat(result).isEqualTo(new Created(durableTask));
        ArgumentCaptor<PlanningRequest> requestCaptor = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(learningTaskPlanner).plan(requestCaptor.capture());
        PlanningRequest planningRequest = requestCaptor.getValue();
        assertThat(planningRequest.languageProfile()).isEqualTo(ENGLISH_PROFILE);
        assertThat(planningRequest.supportLanguage()).isEqualTo("zh-cn");
        assertThat(planningRequest.requestedDifficulty()).isEqualTo(MaterialDifficulty.FOUNDATION);
        assertThat(planningRequest.availableMinutes()).isEqualTo(10);
        assertThat(planningRequest.excludedMaterials()).isEmpty();
        // trusted userId 只来自 UserContext，同时用于 ownership 校验与 durable create。
        verify(languageProfileAccessService).findProfileOwnedByUser(PROFILE_ID, USER_CONTEXT);
        verify(learningTaskRepository).createOwned(USER_ID, plan);
    }

    @Test
    void rejectsProfileNotAccessibleToTheCallerBeforePlanning() {
        when(languageProfileAccessService.findProfileOwnedByUser(PROFILE_ID, USER_CONTEXT))
                .thenReturn(Optional.empty());

        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new LanguageProfileNotFound());

        verifyNoInteractions(learningTaskPlanner, learningTaskRepository);
    }

    @ParameterizedTest
    @EnumSource(PlanningResult.UnavailableReason.class)
    void plannerUnavailableNeverPersists(PlanningResult.UnavailableReason reason) {
        when(learningTaskPlanner.plan(any(PlanningRequest.class)))
                .thenReturn(new PlanningResult.Unavailable(reason));

        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new Unavailable(reason));

        verify(learningTaskRepository, never()).createOwned(any(UUID.class), any(LearningTaskPlan.class));
    }

    @Test
    void forwardsShortButPositiveDurationsToThePlanner() {
        when(learningTaskPlanner.plan(any(PlanningRequest.class)))
                .thenReturn(new PlanningResult.Unavailable(
                        PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT));

        service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 4));

        ArgumentCaptor<PlanningRequest> requestCaptor = ArgumentCaptor.forClass(PlanningRequest.class);
        verify(learningTaskPlanner).plan(requestCaptor.capture());
        assertThat(requestCaptor.getValue().availableMinutes()).isEqualTo(4);
    }

    @Test
    void failsClosedWhenDurableCreateGateRejectsThePlan() {
        when(learningTaskPlanner.plan(any(PlanningRequest.class)))
                .thenReturn(new PlanningResult.Planned(plan()));
        when(learningTaskRepository.createOwned(USER_ID, plan())).thenReturn(Optional.empty());

        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new LanguageProfileNotFound());
    }

    @Test
    void failsClosedWhenPlannerReturnsPlanForAnotherProfileOfTheSameUser() {
        // Repository create gate 只验证 plan 的 Profile 属于同一 user；Planner 是可替换 port，
        // 若其结果绑定到请求之外的 Profile（哪怕同属该 user），必须在落库前被拒绝。
        UUID otherProfileId = UUID.fromString("019cc10c-a56a-7000-8000-000000000013");
        LearningTaskPlan wrongProfilePlan = new LearningTaskPlan(
                otherProfileId,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Order a drink and ask a follow-up question",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        when(learningTaskPlanner.plan(any(PlanningRequest.class)))
                .thenReturn(new PlanningResult.Planned(wrongProfilePlan));

        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new Unavailable(
                        PlanningResult.UnavailableReason.SELECTED_MATERIAL_UNAVAILABLE));

        verifyNoInteractions(learningTaskRepository);
    }

    @ParameterizedTest
    @MethodSource("invalidCommands")
    void invalidRequestIsRejectedBeforeProfileLookup(
            LearningTaskPlanningService.PlanningCommand invalidCommand) {
        assertThat(service.plan(PROFILE_ID, USER_CONTEXT, invalidCommand))
                .isEqualTo(new InvalidRequest());

        verifyNoInteractions(languageProfileAccessService, learningTaskPlanner, learningTaskRepository);
    }

    static Stream<LearningTaskPlanningService.PlanningCommand> invalidCommands() {
        return Stream.of(
                command(null, "FOUNDATION", 10),
                command(" ", "FOUNDATION", 10),
                command("not a tag", "FOUNDATION", 10),
                command("z".repeat(36), "FOUNDATION", 10),
                command("zh-cn", null, 10),
                command("zh-cn", "INTERMEDIATE", 10),
                command("zh-cn", "FOUNDATION", null),
                command("zh-cn", "FOUNDATION", 0),
                command("zh-cn", "FOUNDATION", -3));
    }

    @Test
    void rejectsMissingMandatoryArguments() {
        assertThatThrownBy(() -> service.plan(null, USER_CONTEXT, command("zh-cn", "FOUNDATION", 10)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("languageProfileId must not be null");
        assertThatThrownBy(() -> service.plan(PROFILE_ID, null, command("zh-cn", "FOUNDATION", 10)))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
        assertThatThrownBy(() -> service.plan(PROFILE_ID, USER_CONTEXT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("command must not be null");
    }

    private static LearningTaskPlanningService.PlanningCommand command(
            String supportLanguage,
            String requestedDifficulty,
            Integer availableMinutes) {
        return new LearningTaskPlanningService.PlanningCommand(
                supportLanguage, requestedDifficulty, availableMinutes);
    }

    private static LearningTaskPlan plan() {
        return new LearningTaskPlan(
                PROFILE_ID,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Order a drink and ask a follow-up question",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static LearningTask durableTask() {
        return new LearningTask(
                UUID.randomUUID(),
                USER_ID,
                PROFILE_ID,
                new MaterialIdentity("en-builtin-cafe-request", "v1"),
                "en",
                "zh-cn",
                MaterialDifficulty.FOUNDATION,
                10,
                "CAFE_SIMPLE_REQUEST",
                "Order a drink and ask a follow-up question",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK,
                LearningTask.Status.PLANNED,
                OffsetDateTime.parse("2026-09-04T10:15:30.123Z"),
                Optional.empty(),
                Optional.empty());
    }
}
