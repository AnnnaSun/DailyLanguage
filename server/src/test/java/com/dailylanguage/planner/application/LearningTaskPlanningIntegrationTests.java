package com.dailylanguage.planner.application;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.InvalidRequest;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.LanguageProfileNotFound;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Unavailable;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.domain.PlanningResult;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class LearningTaskPlanningIntegrationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskRepository learningTaskRepository;

    @Autowired
    private LearningTaskPlanningService planningService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsDurableBuiltInTaskForOwnedProfileThroughTheRealCatalog() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        LearningTaskPlanningResult result = planningService.plan(
                profile.id(), new UserContext(ownerId), command(" ZH-CN ", "FOUNDATION", 10));

        assertThat(result).isInstanceOfSatisfying(Created.class, created -> {
            LearningTask task = created.task();
            assertThat(task.id()).isNotNull();
            assertThat(task.userId()).isEqualTo(ownerId);
            assertThat(task.languageProfileId()).isEqualTo(profile.id());
            // exact materialId + publishedVersion 来自 deterministic planner 的稳定选择，不被替换。
            assertThat(task.materialIdentity())
                    .isEqualTo(new MaterialIdentity("en-builtin-cafe-request", "v1"));
            assertThat(task.targetLanguage()).isEqualTo("en");
            assertThat(task.supportLanguage()).isEqualTo("zh-cn");
            assertThat(task.status()).isEqualTo(LearningTask.Status.PLANNED);
            assertThat(task.startedAt()).isEmpty();
            assertThat(task.completedAt()).isEmpty();

            // durable reread 与 row inspection 证明响应语义来自数据库裁决，不是未持久化的 plan。
            assertThat(learningTaskRepository.findOwned(task.id(), ownerId, profile.id()))
                    .contains(task);
            var row = jdbcTemplate.queryForMap(
                    "SELECT user_id, material_id, published_version, status FROM learning_task WHERE id = ?",
                    task.id());
            assertThat(row.get("user_id")).isEqualTo(ownerId);
            assertThat(row.get("material_id")).isEqualTo("en-builtin-cafe-request");
            assertThat(row.get("published_version")).isEqualTo("v1");
            assertThat(row.get("status")).isEqualTo("PLANNED");
        });
    }

    @Test
    void rejectsUnknownAndForeignProfilesWithoutAnyMutation() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity ownerProfile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThat(planningService.plan(
                UUID.randomUUID(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new LanguageProfileNotFound());
        // wrong-owner 与 unknown 返回同一结果，不区分两者。
        assertThat(planningService.plan(
                ownerProfile.id(), new UserContext(otherUserId), command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new LanguageProfileNotFound());
        assertThat(countTasks(ownerProfile.id())).isZero();

        assertThat(planningService.plan(
                ownerProfile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 10)))
                .isInstanceOf(Created.class);
        assertThat(countTasks(ownerProfile.id())).isEqualTo(1);
    }

    @Test
    void doesNotPlanAcrossLanguageIsolationBoundary() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity japaneseProfile = languageProfileRepository
                .create(ownerId, "ja")
                .orElseThrow();

        assertThat(planningService.plan(
                japaneseProfile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 10)))
                .isEqualTo(new Unavailable(PlanningResult.UnavailableReason.NO_ELIGIBLE_MATERIAL));
        assertThat(countTasks(japaneseProfile.id())).isZero();
    }

    @Test
    void unavailableTimeTooShortDoesNotPersistAnything() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        assertThat(planningService.plan(
                profile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 3)))
                .isEqualTo(new Unavailable(PlanningResult.UnavailableReason.AVAILABLE_TIME_TOO_SHORT));
        assertThat(countTasks(profile.id())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a tag", " ", "z"})
    void invalidRequestIsRejectedBeforeAnyDatabaseMutation(String supportLanguage) {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository
                .create(ownerId, "en")
                .orElseThrow();

        LearningTaskPlanningService.PlanningCommand invalidCommand =
                supportLanguage.equals("z")
                        ? command("z".repeat(36), "FOUNDATION", 10)
                        : command(supportLanguage, "FOUNDATION", 10);

        assertThat(planningService.plan(profile.id(), new UserContext(ownerId), invalidCommand))
                .isEqualTo(new InvalidRequest());
        assertThat(planningService.plan(
                profile.id(), new UserContext(ownerId), command("zh-cn", "FOUNDATION", 0)))
                .isEqualTo(new InvalidRequest());
        assertThat(countTasks(profile.id())).isZero();
    }

    private static LearningTaskPlanningService.PlanningCommand command(
            String supportLanguage,
            String requestedDifficulty,
            Integer availableMinutes) {
        return new LearningTaskPlanningService.PlanningCommand(
                supportLanguage, requestedDifficulty, availableMinutes);
    }

    private int countTasks(UUID languageProfileId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM learning_task WHERE language_profile_id = ?",
                Integer.class,
                languageProfileId);
        return count == null ? 0 : count;
    }
}
