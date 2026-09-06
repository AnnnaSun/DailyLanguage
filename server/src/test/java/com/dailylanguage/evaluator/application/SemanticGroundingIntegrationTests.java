package com.dailylanguage.evaluator.application;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_DATABASE_TESTS", matches = "true")
class SemanticGroundingIntegrationTests {

    private static final String ANSWER_TO_GO_TEXT = "To go, please. Thank you!";
    private static final String MODEL_OUTPUT = """
            {"claims":[{"sourceTurnId":"answer-to-go","exactQuote":"Thank you","occurrenceIndex":-1,
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

    private final SemanticGroundingValidator validator = new SemanticGroundingValidator(
            new StructuredOutputValidator(JsonMapper.builder().build()),
            new ClasspathRubricSource());

    @Test
    void groundsModelClaimAgainstDurableOwnerScopedCompletionData() {
        UUID ownerId = userRepository.create();
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
                    .filter(response -> response.stepId().equals("answer-to-go"))
                    .map(LearnerResponse::learnerText)
                    .findFirst().orElseThrow();
            assertThat(durableText).isEqualTo(ANSWER_TO_GO_TEXT);
            assertThat(claim.startOffset()).isEqualTo(durableText.indexOf("Thank you"));
            assertThat(durableText.substring(claim.startOffset(), claim.endOffset())).isEqualTo("Thank you");
        });
    }

    @Test
    void wrongOwnerOrWrongProfileCannotAssemblePrivateResponses() {
        UUID ownerId = userRepository.create();
        UUID otherUserId = userRepository.create();
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
        UUID ownerId = userRepository.create();
        LanguageProfileIdentity profile = languageProfileRepository.create(ownerId, "en").orElseThrow();
        UserContext user = new UserContext(ownerId);
        UUID sessionId = completeCafeSession(profile.id(), user);
        GroundedEvaluationInput trustedInput =
                assembleTrustedInput(profile.id(), ownerId, sessionId);

        PracticeSession sessionBefore = trustedInput.session();
        LearningTask taskBefore = trustedInput.task();
        DeterministicAssessment assessmentBefore = trustedInput.assessment();

        assertThat(validator.validate("{\"claims\":[{\"sourceTurnId\":\"answer-to-go\","
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

        assertThat(practiceService.submit(profileId, sessionId, "order-drink", user,
                "Could I have a medium coffee, please?")).isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "ask-price", user, "How much is it?"))
                .isInstanceOf(SubmitResult.Accepted.class);
        assertThat(practiceService.submit(profileId, sessionId, "answer-to-go", user, ANSWER_TO_GO_TEXT))
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
