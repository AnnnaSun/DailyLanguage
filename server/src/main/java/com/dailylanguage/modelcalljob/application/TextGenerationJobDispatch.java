package com.dailylanguage.modelcalljob.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;

/**
 * 将已经 durable 创建的 Text Generation Job 提交到进程内 execution boundary。
 */
@Component
public class TextGenerationJobDispatch {

    private final ModelCallJobRepository modelCallJobRepository;
    private final TextGenerationJobSubmission submission;

    public TextGenerationJobDispatch(
            ModelCallJobRepository modelCallJobRepository,
            TextGenerationJobSubmission submission) {
        this.modelCallJobRepository = Objects.requireNonNull(
                modelCallJobRepository, "modelCallJobRepository must not be null");
        this.submission = Objects.requireNonNull(submission, "submission must not be null");
    }

    /**
     * Job 必须已在独立事务中提交，异步 Worker 才能从另一个数据库连接认领它。
     */
    @Transactional(propagation = Propagation.NEVER)
    public DispatchResult dispatchCreated(DispatchCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        ModelCallJob job = command.job();
        requireDispatchableIdentity(job, command.request());

        TextGenerationJobWorkItem workItem = new TextGenerationJobWorkItem(
                job.id(),
                job.userId(),
                job.rowVersion(),
                command.request(),
                command.credential());
        SubmissionOutcome outcome = Objects.requireNonNull(
                submission.submit(workItem), "submission outcome must not be null");

        if (outcome == SubmissionOutcome.CAPACITY_UNAVAILABLE) {
            Optional<ModelCallJob> rejectedJob = modelCallJobRepository.tryRecordSubmissionRejection(
                    job.id(), job.userId(), job.rowVersion());
            if (rejectedJob.isEmpty()) {
                throw new IllegalStateException("model call job submission rejection was not recorded");
            }
        }
        return new DispatchResult(job.id(), outcome);
    }

    private static void requireDispatchableIdentity(
            ModelCallJob job, TextGenerationRequest request) {
        if (job.executionStatus() != ModelCallJob.ExecutionStatus.CREATED
                || job.consumptionStatus() != ModelCallJob.ConsumptionStatus.NOT_READY) {
            throw new IllegalStateException("only a CREATED / NOT_READY model call job can be dispatched");
        }
        if (job.modelOperation() != ModelOperation.TEXT_GENERATION
                || job.modelPurpose() != request.purpose()) {
            throw new IllegalArgumentException("text generation request does not match model call job identity");
        }
    }

    public record DispatchCommand(
            ModelCallJob job,
            TextGenerationRequest request,
            TransientProviderCredential credential) {

        public DispatchCommand {
            Objects.requireNonNull(job, "job must not be null");
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(credential, "credential must not be null");
        }
    }

    public record DispatchResult(UUID jobId, SubmissionOutcome submissionOutcome) {

        public DispatchResult {
            Objects.requireNonNull(jobId, "jobId must not be null");
            Objects.requireNonNull(submissionOutcome, "submissionOutcome must not be null");
        }
    }
}
