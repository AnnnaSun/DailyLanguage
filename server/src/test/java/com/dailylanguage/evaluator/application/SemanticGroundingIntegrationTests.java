package com.dailylanguage.evaluator.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.json.JsonMapper;

import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.evaluator.domain.GroundedEvaluationInput;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.GroundedClaim;
import com.dailylanguage.evaluator.domain.SemanticGroundingResult.RejectionReason;
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
 * 用真实 owner-scoped durable Session/Task/responses/assessment 组装 trusted input，验证
 * S7 grounding contract 在数据库裁决数据上的行为——这也是 S8 接入 ModelCallJob 前“trusted
 * input 只能来自 owner-scoped 读取”这一组装前提的回归。
 */
@SpringBootTest(properties = "app.registration-enabled=true")
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class SemanticGroundingIntegrationTests {

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
    private LearningMaterialCatalog materialCatalog;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final SemanticGroundingValidator validator = new SemanticGroundingValidator(
            new StructuredOutputValidator(JsonMapper.builder().build()),
            new ClasspathRubricSource());

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanupCreatedUsers() {
        // planning 走真实非事务边界（plan() 为 NEVER），数据由本测试显式按 FK 依赖顺序清理。
        for (UUID userId : createdUserIds) {
            jdbcTemplate.update("""
                    DELETE FROM practice_response WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM deterministic_step_assessment WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM deterministic_assessment WHERE session_id IN (
                        SELECT session.id FROM practice_session session
                        JOIN learning_task task ON task.id = session.task_id
                        WHERE task.user_id = ?)""", userId);
            jdbcTemplate.update("""
                    DELETE FROM practice_session WHERE task_id IN (
                        SELECT id FROM learning_task WHERE user_id = ?)""", userId);
            jdbcTemplate.update("DELETE FROM learning_task WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM language_profile WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        }
        createdUserIds.clear();
    }

    private UUID newUser() {
        UUID userId = userRepository.create();
        createdUserIds.add(userId);
        return userId;
    }

    @Test
    void groundsModelClaimAgainstDurableOwnerScopedCompletionData() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);

        GroundedEvaluationInput trustedInput =
                assembleTrustedInput(profile.id(), ownerId, sessionId);

        SemanticGroundingResult result = validator.validate(MODEL_OUTPUT, trustedInput);

        assertThat(result).isInstanceOfSatisfying(Validated.class, validated -> {
            assertThat(validated.candidate().languageProfileId()).isEqualTo(profile.id());
            assertThat(validated.candidate().sessionId()).isEqualTo(sessionId);
            assertThat(validated.candidate().targetLanguage()).isEqualTo("en");
            assertThat(validated.candidate().rubricReference())
                    .isEqualTo("builtin-text-communication-rubric/v1");
            assertThat(validated.candidate().claims()).hasSize(1);
            GroundedClaim claim = validated.candidate().claims().getFirst();
            // offsets 必须在数据库原样保存的 learner text 上可复原，而不是内存 fixture。
            String durableText = trustedInput.responses().stream()
                    .filter(response -> response.stepId().equals("order-water-freely"))
                    .map(LearnerResponse::learnerText)
                    .findFirst().orElseThrow();
            assertThat(durableText).isEqualTo(ORDER_WATER_TEXT);
            assertThat(claim.startOffset()).isEqualTo(durableText.indexOf("Thank you"));
            assertThat(durableText.substring(claim.startOffset(), claim.endOffset())).isEqualTo("Thank you");
        });
    }

    @Test
    void wrongOwnerOrWrongProfileCannotAssemblePrivateResponses() {
        UUID ownerId = newUser();
        UUID otherUserId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);

        // 组装前提回归：wrong owner / wrong profile 拿不到任何私有 durable 数据。
        assertThat(practiceSessionRepository.findOwned(sessionId, otherUserId, profile.id())).isEmpty();
        assertThat(practiceSessionRepository.findOwnedResponses(sessionId, otherUserId, profile.id()))
                .isEmpty();
        assertThat(practiceSessionRepository.findOwnedAssessment(sessionId, otherUserId, profile.id()))
                .isEmpty();
        assertThat(practiceSessionRepository.findOwned(sessionId, ownerId, UUID.randomUUID())).isEmpty();
        assertThat(learningTaskRepository.findOwned(
                practiceSessionRepository.findOwned(sessionId, ownerId, profile.id())
                        .orElseThrow().taskId(),
                otherUserId, profile.id())).isEmpty();
    }

    @Test
    void groundingFailureLeavesDurableCompletionAndAssessmentUnchanged() {
        UUID ownerId = newUser();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        GroundedEvaluationInput trustedInput =
                assembleTrustedInput(profile.id(), ownerId, sessionId);

        PracticeSession sessionBefore = trustedInput.session();
        LearningTask taskBefore = trustedInput.task();
        DeterministicAssessment assessmentBefore = trustedInput.assessment();

        assertThat(validator.validate("{\"claims\":[{\"sourceTurnId\":\"order-water-freely\","
                + "\"exactQuote\":\"not in the durable text\",\"occurrenceIndex\":-1,"
                + "\"issueType\":\"GRAMMAR\",\"explanation\":\"fabricated\",\"confidence\":0.9}]}",
                trustedInput))
                .isEqualTo(new SemanticGroundingResult.Rejected(RejectionReason.QUOTE_MISMATCH));

        assertThat(practiceSessionRepository.findOwned(sessionId, ownerId, profile.id()))
                .contains(sessionBefore);
        assertThat(learningTaskRepository.findOwned(taskBefore.id(), ownerId, profile.id()))
                .contains(taskBefore);
        assertThat(practiceSessionRepository.findOwnedAssessment(sessionId, ownerId, profile.id()))
                .contains(assessmentBefore);
    }

    /** plan → start → submit 全部 step → complete：全部走真实 durable 流程。 */
    private UUID completeCafeSession(UUID profileId, UserContext user) {
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
        assertThat(practiceService.submit(profileId, sessionId, "comprehension-check", user,
                "A medium coffee.")).isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "order-water-freely", user, ORDER_WATER_TEXT))
                .isInstanceOf(SubmitResult.Accepted.class);

        CompletionResult completion = practiceService.complete(profileId, sessionId, user);
        assertThat(completion).isInstanceOf(CompletionResult.Created.class);
        return sessionId;
    }

    /** trusted input 只从 owner-scoped durable read 与 exact catalog 解析组装。 */
    private GroundedEvaluationInput assembleTrustedInput(UUID profileId, UUID ownerId, UUID sessionId) {
        PracticeSession session = practiceSessionRepository
                .findOwned(sessionId, ownerId, profileId).orElseThrow();
        LearningTask task = learningTaskRepository
                .findOwned(session.taskId(), ownerId, profileId).orElseThrow();
        DeterministicAssessment assessment = practiceSessionRepository
                .findOwnedAssessment(sessionId, ownerId, profileId).orElseThrow();
        List<LearnerResponse> responses = practiceSessionRepository
                .findOwnedResponses(sessionId, ownerId, profileId);
        MaterialQueryResult queryResult =
                materialCatalog.findByIdentity(task.materialIdentity(), task.supportLanguage());
        assertThat(queryResult).isInstanceOf(MaterialQueryResult.Available.class);
        PublishedLearningMaterial material = ((MaterialQueryResult.Available) queryResult).material();
        return new GroundedEvaluationInput(
                ownerId, profileId, task, session, assessment, responses, material);
    }
}
