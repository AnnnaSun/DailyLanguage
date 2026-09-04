package com.dailylanguage.planner.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.LearningTaskPlan;
import com.dailylanguage.user.infrastructure.UserRepository;

@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class LearningTaskPersistenceIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskRepository learningTaskRepository;

    @Test
    void createsPlannedOwnedTaskThatRoundTripsPlanExactly() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTaskPlan plan = planFor(profile, "en");

        Optional<LearningTask> created = learningTaskRepository.createOwned(ownerId, plan);

        assertThat(created).isPresent();
        LearningTask task = created.orElseThrow();
        assertThat(task.id().version()).isEqualTo(7);
        assertThat(task.userId()).isEqualTo(ownerId);
        assertThat(task.languageProfileId()).isEqualTo(profile.id());
        assertThat(task.materialIdentity()).isEqualTo(
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1+snapshot"));
        assertThat(task.targetLanguage()).isEqualTo("en");
        assertThat(task.supportLanguage()).isEqualTo("zh");
        assertThat(task.difficulty()).isEqualTo(MaterialDifficulty.FOUNDATION);
        assertThat(task.estimatedDurationMinutes()).isEqualTo(7);
        assertThat(task.scenario()).isEqualTo("Ordering breakfast at a café");
        assertThat(task.primaryGoal()).isEqualTo("Ask the staff a follow-up question about today's specials");
        assertThat(task.taskType()).isEqualTo(LearningTaskPlan.TaskType.TEXT_PRACTICE);
        assertThat(task.planningReason()).isEqualTo(
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
        assertThat(task.status()).isEqualTo(LearningTask.Status.PLANNED);
        assertThat(task.createdAt()).isNotNull();
        assertThat(task.startedAt()).isEmpty();
        assertThat(task.completedAt()).isEmpty();
        assertThat(learningTaskRepository.findOwned(task.id(), ownerId, profile.id()))
                .contains(task);
    }

    @Test
    void doesNotCreateTaskWhenTargetLanguageDiffersFromProfile() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThat(learningTaskRepository.createOwned(ownerId, planFor(profile, "ja")))
                .isEmpty();

        // 同一 Profile 上改用匹配的 target language 后可以正常创建，证明前一次尝试没有留下任何 row。
        assertThat(learningTaskRepository.createOwned(ownerId, planFor(profile, "en"))).isPresent();
    }

    @Test
    void doesNotCreateTaskWhenProfileBelongsToAnotherUser() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity ownerProfile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThat(learningTaskRepository.createOwned(otherUserId, planFor(ownerProfile, "en")))
                .isEmpty();

        assertThat(learningTaskRepository.createOwned(ownerId, planFor(ownerProfile, "en")))
                .isPresent();
    }

    @Test
    void doesNotCreateTaskForUnknownProfile() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity unknownProfile =
                new LanguageProfileIdentity(UUID.randomUUID(), ownerId, "en");

        assertThat(learningTaskRepository.createOwned(ownerId, planFor(unknownProfile, "en")))
                .isEmpty();
    }

    @Test
    void hidesTaskFromOtherUserAndOtherLanguageProfile() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity englishProfile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LanguageProfileIdentity japaneseProfile = languageProfileRepository
                .create(ownerId, "ja")
                .orElseThrow();
        LearningTask task = learningTaskRepository
                .createOwned(ownerId, planFor(englishProfile, "en"))
                .orElseThrow();

        assertThat(learningTaskRepository.findOwned(task.id(), otherUserId, englishProfile.id()))
                .isEmpty();
        assertThat(learningTaskRepository.findOwned(task.id(), ownerId, japaneseProfile.id()))
                .isEmpty();
        assertThat(learningTaskRepository.findOwned(task.id(), ownerId, englishProfile.id()))
                .contains(task);
    }

    @Test
    void startsPlannedTaskExactlyOnce() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTask planned = learningTaskRepository
                .createOwned(ownerId, planFor(profile, "en"))
                .orElseThrow();

        Optional<LearningTask> started = learningTaskRepository.tryStart(
                planned.id(), ownerId, profile.id());

        assertThat(started).isPresent();
        LearningTask startedTask = started.orElseThrow();
        assertThat(startedTask.status()).isEqualTo(LearningTask.Status.STARTED);
        assertThat(startedTask.startedAt()).isPresent()
                .get()
                .satisfies(startedAt -> assertThat(startedAt).isAfterOrEqualTo(planned.createdAt()));
        assertThat(startedTask.completedAt()).isEmpty();

        assertThat(learningTaskRepository.tryStart(planned.id(), ownerId, profile.id())).isEmpty();
        Optional<LearningTask> reread = learningTaskRepository.findOwned(
                planned.id(), ownerId, profile.id());
        assertThat(reread).contains(startedTask);
    }

    @Test
    void completesStartedTaskExactlyOnceAndRejectsSkipAndReverseTransitions() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTask planned = learningTaskRepository
                .createOwned(ownerId, planFor(profile, "en"))
                .orElseThrow();

        // 跳级 PLANNED → COMPLETED 不允许。
        assertThat(learningTaskRepository.tryComplete(planned.id(), ownerId, profile.id()))
                .isEmpty();
        assertThat(learningTaskRepository.findOwned(planned.id(), ownerId, profile.id()))
                .contains(planned);

        LearningTask started = learningTaskRepository
                .tryStart(planned.id(), ownerId, profile.id())
                .orElseThrow();
        LearningTask completed = learningTaskRepository
                .tryComplete(planned.id(), ownerId, profile.id())
                .orElseThrow();

        assertThat(completed.status()).isEqualTo(LearningTask.Status.COMPLETED);
        assertThat(completed.startedAt()).isEqualTo(started.startedAt());
        assertThat(completed.completedAt()).isPresent()
                .get()
                .satisfies(completedAt ->
                        assertThat(completedAt).isAfterOrEqualTo(started.startedAt().orElseThrow()));

        // 重复与逆向 transition 均为 no-op。
        assertThat(learningTaskRepository.tryComplete(planned.id(), ownerId, profile.id()))
                .isEmpty();
        assertThat(learningTaskRepository.tryStart(planned.id(), ownerId, profile.id()))
                .isEmpty();
        assertThat(learningTaskRepository.findOwned(planned.id(), ownerId, profile.id()))
                .contains(completed);
    }

    @Test
    void rejectsTransitionsForWrongOwnerOrWrongLanguageProfile() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity englishProfile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LanguageProfileIdentity japaneseProfile = languageProfileRepository
                .create(ownerId, "ja")
                .orElseThrow();
        LearningTask planned = learningTaskRepository
                .createOwned(ownerId, planFor(englishProfile, "en"))
                .orElseThrow();

        assertThat(learningTaskRepository.tryStart(
                planned.id(), otherUserId, englishProfile.id())).isEmpty();
        assertThat(learningTaskRepository.tryStart(
                planned.id(), ownerId, japaneseProfile.id())).isEmpty();
        assertThat(learningTaskRepository.tryComplete(
                planned.id(), otherUserId, englishProfile.id())).isEmpty();
        assertThat(learningTaskRepository.tryComplete(
                planned.id(), ownerId, japaneseProfile.id())).isEmpty();

        assertThat(learningTaskRepository.findOwned(planned.id(), ownerId, englishProfile.id()))
                .contains(planned);
    }

    @Test
    void surfacesDatabaseConstraintViolationInsteadOfEmptyResult() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTaskPlan invalidPlan = new LearningTaskPlan(
                profile.id(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1+snapshot"),
                "en",
                "ZH",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);

        // 违反 durable constraint 属于 programming error，必须显式失败，不能伪装成业务 unavailable。
        assertThatThrownBy(() -> learningTaskRepository.createOwned(ownerId, invalidPlan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMissingOwnershipArguments() {
        UUID taskId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();

        assertThatThrownBy(() -> learningTaskRepository.findOwned(null, userId, profileId))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("taskId must not be null");
        assertThatThrownBy(() -> learningTaskRepository.tryStart(taskId, null, profileId))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("trustedUserId must not be null");
        assertThatThrownBy(() -> learningTaskRepository.tryComplete(taskId, userId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("languageProfileId must not be null");
        assertThatThrownBy(() -> learningTaskRepository.createOwned(userId, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("plan must not be null");
    }

    private static LearningTaskPlan planFor(LanguageProfileIdentity profile, String targetLanguage) {
        return new LearningTaskPlan(
                profile.id(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1+snapshot"),
                targetLanguage,
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }
}
