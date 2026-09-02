package com.dailylanguage.modelcalljob.application;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;

/**
 * 创建持久化 Text Generation Job，并将只存在于内存的执行参数提交给 execution boundary。
 */
@Component
public class TextGenerationJobStart {

    private final ModelCallJobRepository modelCallJobRepository;
    private final TextGenerationJobSubmission submission;

    public TextGenerationJobStart(
            ModelCallJobRepository modelCallJobRepository,
            TextGenerationJobSubmission submission) {
        this.modelCallJobRepository = Objects.requireNonNull(
                modelCallJobRepository, "modelCallJobRepository must not be null");
        this.submission = Objects.requireNonNull(submission, "submission must not be null");
    }

    /**
     * Job 的数据库 INSERT 必须先完成提交，异步 Worker 才能从另一个数据库连接认领它。
     */
    @Transactional(propagation = Propagation.NEVER)
    public StartResult start(StartCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        NewModelCallJob newJob = new NewModelCallJob(
                command.userId(),
                command.languageProfileId(),
                command.request().purpose(),
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                command.workflowId(),
                command.workflowStepId(),
                command.workflowVersion(),
                command.expiresAt());
        ModelCallJob createdJob = modelCallJobRepository.create(newJob);
        TextGenerationJobWorkItem workItem = new TextGenerationJobWorkItem(
                createdJob.id(),
                createdJob.userId(),
                createdJob.rowVersion(),
                command.request(),
                command.credential());
        SubmissionOutcome outcome = Objects.requireNonNull(
                submission.submit(workItem), "submission outcome must not be null");

        if (outcome == SubmissionOutcome.CAPACITY_UNAVAILABLE) {
            Optional<ModelCallJob> rejectedJob = modelCallJobRepository.tryRecordSubmissionRejection(
                    createdJob.id(), createdJob.userId(), createdJob.rowVersion());
            if (rejectedJob.isEmpty()) {
                throw new IllegalStateException("model call job submission rejection was not recorded");
            }
        }
        return new StartResult(createdJob.id(), outcome);
    }

    public record StartCommand(
            UUID userId,
            Optional<UUID> languageProfileId,
            UUID workflowId,
            String workflowStepId,
            long workflowVersion,
            OffsetDateTime expiresAt,
            TextGenerationRequest request,
            TransientProviderCredential credential) {

        public StartCommand {
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
            Objects.requireNonNull(workflowId, "workflowId must not be null");
            Objects.requireNonNull(workflowStepId, "workflowStepId must not be null");
            if (workflowVersion < 0) {
                throw new IllegalArgumentException("workflowVersion must not be negative");
            }
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(credential, "credential must not be null");
        }
    }

    public record StartResult(UUID jobId, SubmissionOutcome submissionOutcome) {

        public StartResult {
            Objects.requireNonNull(jobId, "jobId must not be null");
            Objects.requireNonNull(submissionOutcome, "submissionOutcome must not be null");
        }
    }
}
