package com.dailylanguage.planner.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch;
import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.planner.config.PlannerEnrichmentProperties;
import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.security.domain.UserContext;

/**
 * 准备 versioned Planner enrichment request，durable 创建 Run / Job / candidate snapshot 后
 * 提交 memory-only execution input。只负责 dispatch：不等待、不消费结果、不创建 LearningTask；
 * unexpected submission failure 原样传播，不猜测 Worker 是否已接受。
 */
@Service
public class PlannerEnrichmentDispatchService {

    private final PlannerEnrichmentTextRequestFactory requestFactory;
    private final PlanningRunCreationService runCreationService;
    private final TextGenerationJobDispatch jobDispatch;
    private final Duration resultTtl;

    public PlannerEnrichmentDispatchService(
            PlannerEnrichmentTextRequestFactory requestFactory,
            PlanningRunCreationService runCreationService,
            TextGenerationJobDispatch jobDispatch,
            PlannerEnrichmentProperties properties) {
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
        this.runCreationService =
                Objects.requireNonNull(runCreationService, "runCreationService must not be null");
        this.jobDispatch = Objects.requireNonNull(jobDispatch, "jobDispatch must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.resultTtl = properties.resultTtl();
    }

    /**
     * 外层禁止事务：S9C2 的 REQUIRES_NEW 返回后 Run / Job / snapshot 已提交，之后才允许
     * 异步 Worker 从另一个数据库连接认领。入口预检 user/profile 一致性只用于尽早暴露
     * internal programming error、避免无意义的 Job 创建与事务回滚；不替代 S9C2 数据库
     * owner/profile composite FK 与 insert gate 的最终裁决。Credential 只在当前调用链内存中
     * 传播，不进入 DB、result、log 或 trace。
     */
    @Transactional(propagation = Propagation.NEVER)
    public DispatchResult dispatch(
            PlanningRequest request,
            PlanningCandidateSet candidateSet,
            UserContext userContext,
            TransientProviderCredential credential) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(candidateSet, "candidateSet must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        if (!userContext.userId().equals(request.languageProfile().userId())) {
            throw new IllegalArgumentException(
                    "userContext must match the planning request language profile owner");
        }

        PlannerEnrichmentTextRequestFactory.BuildResult buildResult =
                requestFactory.build(request, candidateSet);
        if (buildResult instanceof PlannerEnrichmentTextRequestFactory.BuildResult.Unavailable unavailable) {
            return new DispatchResult.Unavailable(unavailable.reason());
        }
        TextGenerationRequest textRequest =
                ((PlannerEnrichmentTextRequestFactory.BuildResult.ReadyRequest) buildResult).request();

        PlanningRunCreationService.Created created = runCreationService.create(
                userContext,
                candidateSet,
                OffsetDateTime.now(ZoneOffset.UTC).plus(resultTtl));

        TextGenerationJobDispatch.DispatchResult dispatchResult = jobDispatch.dispatchCreated(
                new DispatchCommand(created.job(), textRequest, credential));
        return new DispatchResult.Created(
                created.run(), dispatchResult.jobId(), dispatchResult.submissionOutcome());
    }

    public sealed interface DispatchResult {

        record Created(
                PlanningRun run,
                UUID jobId,
                SubmissionOutcome submissionOutcome) implements DispatchResult {

            public Created {
                Objects.requireNonNull(run, "run must not be null");
                Objects.requireNonNull(jobId, "jobId must not be null");
                Objects.requireNonNull(submissionOutcome, "submissionOutcome must not be null");
            }
        }

        /** request 构造失败；未创建任何 durable 状态，也未提交 Provider 调用。 */
        record Unavailable(
                PlannerEnrichmentTextRequestFactory.UnavailabilityReason reason) implements DispatchResult {

            public Unavailable {
                Objects.requireNonNull(reason, "reason must not be null");
            }
        }
    }
}
