package com.dailylanguage.evaluator.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.evaluator.application.EvaluationRunCreationService.CreationResult;
import com.dailylanguage.evaluator.application.EvaluationTextRequestFactory.BuildResult;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.security.domain.UserContext;

/**
 * 准备 versioned Evaluator request，durable 创建 Run / Job 后提交 memory-only execution input。
 */
@Service
public class EvaluationDispatchService {

    private final EvaluationTextRequestFactory requestFactory;
    private final EvaluationRunCreationService runCreationService;
    private final TextGenerationJobDispatch jobDispatch;
    private final Duration resultTtl;

    public EvaluationDispatchService(
            EvaluationTextRequestFactory requestFactory,
            EvaluationRunCreationService runCreationService,
            TextGenerationJobDispatch jobDispatch,
            @Value("${app.evaluator.result-ttl}") Duration resultTtl) {
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        this.runCreationService = Objects.requireNonNull(
                runCreationService, "runCreationService must not be null");
        this.jobDispatch = Objects.requireNonNull(jobDispatch, "jobDispatch must not be null");
        this.resultTtl = requirePositive(resultTtl);
    }

    /**
     * 外层禁止事务：S8B 的 REQUIRES_NEW 返回后 Run / Job 已提交，之后才允许异步 Worker 认领。
     */
    @Transactional(propagation = Propagation.NEVER)
    public DispatchResult dispatchForReadyInput(
            GroundedEvaluationInputResult.Ready ready,
            UserContext userContext,
            TransientProviderCredential credential) {
        Objects.requireNonNull(ready, "ready must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        Objects.requireNonNull(credential, "credential must not be null");

        BuildResult buildResult = requestFactory.build(
                ready, EvaluationRunCreationService.WORKFLOW_VERSION);
        if (buildResult instanceof BuildResult.Unavailable unavailable) {
            return new DispatchResult.Unavailable(unavailable.reason());
        }
        TextGenerationRequest request = ((BuildResult.ReadyRequest) buildResult).request();
        OffsetDateTime resultExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plus(resultTtl);
        CreationResult creationResult = runCreationService.createForReadyInput(
                ready, userContext, resultExpiresAt);

        return switch (creationResult) {
            case CreationResult.Created created -> dispatchCreated(created, request, credential);
            case CreationResult.Existing existing ->
                    new DispatchResult.Existing(existing.run(), existing.job());
            case CreationResult.NotFound ignored -> new DispatchResult.NotFound();
            case CreationResult.NotCompleted ignored -> new DispatchResult.NotCompleted();
            case CreationResult.InconsistentInput ignored -> new DispatchResult.InconsistentInput();
        };
    }

    private DispatchResult.Created dispatchCreated(
            CreationResult.Created created,
            TextGenerationRequest request,
            TransientProviderCredential credential) {
        TextGenerationJobDispatch.DispatchResult dispatchResult = jobDispatch.dispatchCreated(
                new DispatchCommand(created.job(), request, credential));
        return new DispatchResult.Created(
                created.run(), dispatchResult.jobId(), dispatchResult.submissionOutcome());
    }

    private static Duration requirePositive(Duration duration) {
        Objects.requireNonNull(duration, "resultTtl must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("resultTtl must be positive");
        }
        return duration;
    }

    public sealed interface DispatchResult {

        record Created(
                EvaluationRun run,
                UUID jobId,
                SubmissionOutcome submissionOutcome) implements DispatchResult {

            public Created {
                Objects.requireNonNull(run, "run must not be null");
                Objects.requireNonNull(jobId, "jobId must not be null");
                Objects.requireNonNull(submissionOutcome, "submissionOutcome must not be null");
            }
        }

        /** 已有 Run / Job 只返回 durable 状态，不重新提交 Provider 调用。 */
        record Existing(EvaluationRun run, ModelCallJob job) implements DispatchResult {

            public Existing {
                Objects.requireNonNull(run, "run must not be null");
                Objects.requireNonNull(job, "job must not be null");
            }
        }

        record Unavailable(
                EvaluationTextRequestFactory.UnavailabilityReason reason) implements DispatchResult {

            public Unavailable {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }

        record NotFound() implements DispatchResult {
        }

        record NotCompleted() implements DispatchResult {
        }

        record InconsistentInput() implements DispatchResult {
        }
    }
}
