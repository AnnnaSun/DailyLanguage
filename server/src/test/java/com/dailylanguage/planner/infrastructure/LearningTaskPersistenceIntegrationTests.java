package com.dailylanguage.planner.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        assertThat(task.recommendationReason()).isEmpty();
        assertThat(task.status()).isEqualTo(LearningTask.Status.PLANNED);
        assertThat(task.createdAt()).isNotNull();
        assertThat(task.startedAt()).isEmpty();
        assertThat(task.completedAt()).isEmpty();
        assertThat(learningTaskRepository.findOwned(task.id(), ownerId, profile.id()))
                .contains(task);
    }

    @Test
    void createsModelEnrichedOwnedTaskThatRoundTripsRecommendationReason() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTaskPlan enrichedPlan = new LearningTaskPlan(
                profile.id(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1+snapshot"),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of("今天在咖啡馆练习点单，并追问今日特供。"));

        Optional<LearningTask> created = learningTaskRepository.createOwned(ownerId, enrichedPlan);

        assertThat(created).isPresent();
        LearningTask task = created.orElseThrow();
        assertThat(task.planningReason()).isEqualTo(LearningTaskPlan.PlanningReason.MODEL_ENRICHED);
        assertThat(task.recommendationReason()).contains("今天在咖啡馆练习点单，并追问今日特供。");
        assertThat(task.status()).isEqualTo(LearningTask.Status.PLANNED);
        assertThat(learningTaskRepository.findOwned(task.id(), ownerId, profile.id()))
                .contains(task);
    }

    @Test
    void roundTripsMaximumCodePointRecommendationReason() {
        // 239 个 ASCII + 1 个 astral 字符：240 个 code points、241 个 UTF-16 chars，
        // 证明列边界按 Unicode code points 而不是 Java chars 裁决。
        String maximumReason = "x".repeat(239) + "\uD835\uDD4A";
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();
        LearningTaskPlan enrichedPlan = new LearningTaskPlan(
                profile.id(),
                new MaterialIdentity("builtin:text-practice/morning-routine", "2026.03.1+snapshot"),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of(maximumReason));

        Optional<LearningTask> created = learningTaskRepository.createOwned(ownerId, enrichedPlan);

        assertThat(created).isPresent();
        assertThat(created.orElseThrow().recommendationReason()).contains(maximumReason);
    }

    @Test
    void rejectsModelEnrichedRowWithoutRecommendationReasonAtDatabaseLevel() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        // 绕过 Java domain 直写数据库：pairing 属于 durable constraint，必须在持久层同样 fail closed。
        assertThatThrownBy(() -> insertRawLearningTaskRow(
                ownerId, profile.id(), "MODEL_ENRICHED", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDeterministicRowWithRecommendationReasonAtDatabaseLevel() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThatThrownBy(() -> insertRawLearningTaskRow(
                ownerId, profile.id(), "DETERMINISTIC_BUILT_IN_FALLBACK", "today's specials"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            " reason with leading whitespace",
            "reason with trailing whitespace ",
            "reason\twith tab",
            "reason\nwith newline",
            "reason\rwith carriage return",
            "reason\u2028with line separator",
            "reason\u2029with paragraph separator",
            "cafe\u0301"
    })
    void rejectsInvalidRecommendationReasonTextAtDatabaseLevel(String invalidReason) {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        // 与 Domain tests 平行的 durable constraint regression：reason 文本边界由数据库层
        // 独立裁决，防止 Java 校验与 ck_learning_task_recommendation_reason 漂移。
        assertThatThrownBy(() -> insertRawLearningTaskRow(
                ownerId, profile.id(), "MODEL_ENRICHED", invalidReason))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsRecommendationReasonExceedingColumnLimitAtDatabaseLevel() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        // 241 code points 由 VARCHAR(240) 列边界拒绝（SQLSTATE 22001），同样 fail closed。
        assertThatThrownBy(() -> insertRawLearningTaskRow(
                ownerId, profile.id(), "MODEL_ENRICHED", "x".repeat(241)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRawLearningTaskRow(
            UUID ownerId, UUID languageProfileId, String planningReason, String recommendationReason) {
        jdbcTemplate.update(
                "INSERT INTO learning_task (user_id, language_profile_id, material_id, published_version,"
                        + " support_language, difficulty, estimated_duration_minutes, scenario, primary_goal,"
                        + " task_type, planning_reason, recommendation_reason)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ownerId,
                languageProfileId,
                "builtin:text-practice/morning-routine",
                "2026.03.1+snapshot",
                "zh",
                "FOUNDATION",
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                "TEXT_PRACTICE",
                planningReason,
                recommendationReason);
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
