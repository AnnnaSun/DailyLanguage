package com.dailylanguage.evaluator.application;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.InconsistentSnapshot;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.NotFound;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.NotCompleted;
import com.dailylanguage.evaluator.application.GroundedEvaluationInputResult.Ready;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.Validated;
import com.dailylanguage.languageprofile.domain.LanguageProfileIdentity;
import com.dailylanguage.languageprofile.infrastructure.LanguageProfileRepository;
import com.dailylanguage.modelgateway.structuredoutput.StructuredOutputValidator;
import com.dailylanguage.planner.application.LearningTaskPlanningResult;
import com.dailylanguage.planner.application.LearningTaskPlanningResult.Created;
import com.dailylanguage.planner.application.LearningTaskPlanningService;
import com.dailylanguage.planner.domain.LearningTask;
import com.dailylanguage.planner.infrastructure.LearningTaskRepository;
import com.dailylanguage.practice.application.PracticeSessionApplicationService;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.CompletionResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.StartResult;
import com.dailylanguage.practice.application.PracticeSessionApplicationService.SubmitResult;
import com.dailylanguage.practice.domain.DeterministicAssessment;
import com.dailylanguage.practice.domain.PracticeSession;
import com.dailylanguage.practice.domain.PracticeSession.LearnerResponse;
import com.dailylanguage.practice.infrastructure.PracticeSessionRepository;
import com.dailylanguage.security.domain.UserContext;
import com.dailylanguage.user.infrastructure.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用真实 PostgreSQL durable 数据验证 S8A Reader 入口：owner-scoped 读取、completion gate、
 * snapshot 完整性与零写入都在数据库裁决数据上成立，Ready 产出的 trusted input 能直接进入
 * S7 grounding validator 且 offsets 对应 durable learner text。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class GroundedEvaluationInputReaderIntegrationTests {

    // v2 guided cafe material 的第三个 step（INDEPENDENT_TRANSFER / SEMANTIC_ONLY）自由作答文本；
    // MODEL_OUTPUT 的 exactQuote "Thank you" 必须出现在该文本中供 grounding offset 断言使用。
    private static final String ORDER_WATER_TEXT = "A bottle of water, please. Thank you!";
    private static final String MODEL_OUTPUT = """
            {"claims":[{"sourceTurnId":"order-water-freely","exactQuote":"Thank you","occurrenceIndex":-1,
            "issueType":"NATURALNESS","explanation":"The quoted thanks reads as abrupt here.",
            "confidence":0.7}]}
            """;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LanguageProfileRepository languageProfileRepository;

    @Autowired
    private LearningTaskPlanningService planningService;

    @Autowired
    private PracticeSessionApplicationService practiceService;

    @Autowired
    private LearningTaskRepository learningTaskRepository;

    @Autowired
    private PracticeSessionRepository practiceSessionRepository;

    @Autowired
    private GroundedEvaluationInputReader reader;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SqlSessionTemplate sqlSession;

    private final SemanticGroundingValidator validator = new SemanticGroundingValidator(
            new StructuredOutputValidator(JsonMapper.builder().build()),
            new ClasspathRubricSource());

    @Test
    void readyInputCarriesDurableLearnerTextAndFeedsGroundingValidator() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);

        GroundedEvaluationInputResult result = reader.readOwned(profile.id(), sessionId, user);

        assertThat(result).isInstanceOfSatisfying(Ready.class, ready -> {
            GroundedEvaluationInput input = ready.input();
            assertThat(input.userId()).isEqualTo(ownerId);
            assertThat(input.languageProfileId()).isEqualTo(profile.id());
            assertThat(input.session().id()).isEqualTo(sessionId);
            assertThat(input.session().status()).isEqualTo(PracticeSession.Status.COMPLETED);
            assertThat(input.task().status()).isEqualTo(LearningTask.Status.COMPLETED);
            assertThat(input.task().materialIdentity()).isEqualTo(input.material().identity());
            assertThat(input.assessment().sessionId()).isEqualTo(sessionId);
            // durable learner text 原样进入 input，不 strip、不 normalize。
            assertThat(input.responses())
                    .extracting(LearnerResponse::learnerText)
                    .containsExactlyInAnyOrder(
                            "A medium coffee.",
                            "Could I have a medium coffee, please?",
                            ORDER_WATER_TEXT);
            assertThat(input.responses())
                    .allSatisfy(response -> assertThat(response.sessionId()).isEqualTo(sessionId));

            // Reader 产出的 trusted input 直接满足 S7 grounding；offsets 在 durable text 上可复原。
            SemanticGroundingResult grounding = validator.validate(MODEL_OUTPUT, input);
            assertThat(grounding).isInstanceOfSatisfying(Validated.class, validated -> {
                assertThat(validated.candidate().sessionId()).isEqualTo(sessionId);
                GroundedClaim claim = validated.candidate().claims().getFirst();
                assertThat(claim.startOffset()).isEqualTo(ORDER_WATER_TEXT.indexOf("Thank you"));
                assertThat(ORDER_WATER_TEXT.substring(claim.startOffset(), claim.endOffset()))
                        .isEqualTo("Thank you");
            });
        });
    }

    @Test
    void unknownWrongOwnerOrWrongProfileSessionsAreIndistinguishableNotFound() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        // 同一用户的另一 Profile（UNIQUE(user_id, language_code) 下不同语言）。
        LanguageProfileIdentity ownerOtherProfile =
                languageProfileRepository.create(ownerId, "ja").orElseThrow();
        // 另一用户的同目标语言 Profile。
        LanguageProfileIdentity otherUserEnProfile =
                languageProfileRepository.create(otherUserId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);

        assertThat(reader.readOwned(profile.id(), sessionId, new UserContext(otherUserId)))
                .isEqualTo(new NotFound());
        assertThat(reader.readOwned(otherUserEnProfile.id(), sessionId, new UserContext(otherUserId)))
                .isEqualTo(new NotFound());
        assertThat(reader.readOwned(ownerOtherProfile.id(), sessionId, user))
                .isEqualTo(new NotFound());
        assertThat(reader.readOwned(profile.id(), UUID.randomUUID(), user))
                .isEqualTo(new NotFound());
    }

    @Test
    void inProgressOwnedSessionIsNotCompleted() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        // startCafeSession 已接受 order-with-frame response；Session 保持 IN_PROGRESS（不再重复提交，
        // 相同 payload 的第二次 submit 是 Replayed 而非 Accepted）。
        UUID sessionId = startCafeSession(profile.id(), user);

        assertThat(reader.readOwned(profile.id(), sessionId, user)).isEqualTo(new NotCompleted());
    }

    @Test
    void successfulAndFailedReadsLeaveDurableStateUnchanged() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);

        // 前后比较必须反映数据库事实：清空 MyBatis 一级缓存，避免同一事务内重复 SELECT
        // 直接命中 completion 阶段已缓存的行。
        clearMyBatisLocalCache();
        PracticeSession sessionBefore = practiceSessionRepository
                .findOwned(sessionId, ownerId, profile.id()).orElseThrow();
        LearningTask taskBefore = learningTaskRepository
                .findOwned(sessionBefore.taskId(), ownerId, profile.id()).orElseThrow();
        DeterministicAssessment assessmentBefore = practiceSessionRepository
                .findOwnedAssessment(sessionId, ownerId, profile.id()).orElseThrow();
        List<LearnerResponse> responsesBefore = practiceSessionRepository
                .findOwnedResponses(sessionId, ownerId, profile.id());

        assertThat(reader.readOwned(profile.id(), sessionId, user)).isInstanceOf(Ready.class);
        assertThat(reader.readOwned(profile.id(), sessionId, new UserContext(otherUserId)))
                .isEqualTo(new NotFound());
        assertThat(reader.readOwned(profile.id(), UUID.randomUUID(), user)).isEqualTo(new NotFound());

        // 再次清缓存后重读，确认成功与失败读取都没有改变 durable Practice / assessment 数据。
        clearMyBatisLocalCache();
        assertThat(practiceSessionRepository.findOwned(sessionId, ownerId, profile.id()))
                .contains(sessionBefore);
        assertThat(learningTaskRepository.findOwned(taskBefore.id(), ownerId, profile.id()))
                .contains(taskBefore);
        assertThat(practiceSessionRepository.findOwnedAssessment(sessionId, ownerId, profile.id()))
                .contains(assessmentBefore);
        assertThat(practiceSessionRepository.findOwnedResponses(sessionId, ownerId, profile.id()))
                .containsExactlyInAnyOrderElementsOf(responsesBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM practice_session WHERE id = ?", String.class, sessionId))
                .isEqualTo("COMPLETED");
    }

    @Test
    void completedSessionWithoutDurableAssessmentIsInconsistentSnapshot() {
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);

        // completed Session 的 assessment 缺失属于 durable 结构异常，不能误判为 NotCompleted。
        jdbcTemplate.update("DELETE FROM deterministic_step_assessment WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM deterministic_assessment WHERE session_id = ?", sessionId);
        // JdbcTemplate 绕过 MyBatis，不会失效同一事务 SqlSession 的一级缓存；不清缓存 Reader
        // 会命中 completion 阶段缓存的 assessment 行而误返回 Ready。
        clearMyBatisLocalCache();

        assertThat(reader.readOwned(profile.id(), sessionId, user))
                .isEqualTo(new InconsistentSnapshot());
    }

    /** 仅测试内清空当前事务 SqlSession 的 MyBatis 一级缓存（不改 Production cache 配置）：
     *  JdbcTemplate 的 DELETE/写入不经过 MyBatis，local cache 中的旧行不会自动失效。 */
    private void clearMyBatisLocalCache() {
        sqlSession.clearCache();
    }

    /** plan → start → submit 全部 step → complete：全部走真实 durable 流程（planner 选 guided cafe v2）。 */
    private UUID completeCafeSession(UUID profileId, UserContext user) {
        UUID sessionId = startCafeSession(profileId, user);
        assertThat(practiceService.submit(profileId, sessionId, "comprehension-check", user,
                "A medium coffee.")).isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "order-water-freely", user, ORDER_WATER_TEXT))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.complete(profileId, sessionId, user))
                .isInstanceOf(CompletionResult.Created.class);
        return sessionId;
    }

    private UUID startCafeSession(UUID profileId, UserContext user) {
        LearningTaskPlanningResult planningResult = planningService.plan(
                profileId, user,
                new LearningTaskPlanningService.PlanningCommand("zh-cn", "FOUNDATION", 10));
        assertThat(planningResult).isInstanceOf(Created.class);
        LearningTask task = ((Created) planningResult).task();

        StartResult startResult = practiceService.start(profileId, task.id(), user);
        assertThat(startResult).isInstanceOf(StartResult.Created.class);
        UUID sessionId = ((StartResult.Created) startResult).session().id();

        assertThat(practiceService.submit(profileId, sessionId, "order-with-frame", user,
                "Could I have a medium coffee, please?")).isInstanceOf(SubmitResult.Accepted.class);
        return sessionId;
    }
}
