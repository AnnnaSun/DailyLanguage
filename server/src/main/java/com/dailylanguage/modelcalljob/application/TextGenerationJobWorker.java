package com.dailylanguage.modelcalljob.application;

import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.result.ModelFailureKind;
import com.dailylanguage.modelgateway.result.ModelResult;
import com.dailylanguage.modelgateway.text.TextGenerationPort;
import com.dailylanguage.modelgateway.text.TextGenerationResponse;

/**
 * 第一个 typed Job worker：认领已创建的 Text Generation Job，经 Gateway 执行并写入终态。
 * 不负责提交、排队或恢复；投递方式由未来的 submission boundary 决定。
 */
@Component
public class TextGenerationJobWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextGenerationJobWorker.class);

    private final ModelCallJobRepository modelCallJobRepository;
    private final TextGenerationPort textGenerationPort;

    public TextGenerationJobWorker(
            ModelCallJobRepository modelCallJobRepository,
            TextGenerationPort textGenerationPort) {
        this.modelCallJobRepository = Objects.requireNonNull(
                modelCallJobRepository, "modelCallJobRepository must not be null");
        this.textGenerationPort = Objects.requireNonNull(
                textGenerationPort, "textGenerationPort must not be null");
    }

    /**
     * 结果分类规则：以 ModelResult 形式跨过 port 的结果是已知的；port 抛出的任何异常
     * 从 worker 视角无法区分"未提交"与"已提交可能已执行"，一律按结果未知处理。
     * claim 本身失败会直接向上传播，此时 Job 仍是 CREATED 且未发生任何外部调用。
     */
    public WorkerOutcome execute(TextGenerationJobWorkItem workItem) {
        Objects.requireNonNull(workItem, "workItem must not be null");
        Optional<ModelCallJob> claimedJob = modelCallJobRepository.tryStartExecution(
                workItem.jobId(), workItem.userId(), workItem.expectedRowVersion());
        if (claimedJob.isEmpty()) {
            return WorkerOutcome.CLAIM_LOST;
        }
        long runningRowVersion = claimedJob.orElseThrow().rowVersion();

        ModelResult<TextGenerationResponse> result;
        try {
            result = textGenerationPort.generateText(workItem.request(), workItem.credential());
        }
        catch (RuntimeException failure) {
            return recordOutcomeUnknownBestEffort(workItem, runningRowVersion, failure);
        }
        catch (Error failure) {
            recordOutcomeUnknownBestEffort(workItem, runningRowVersion, failure);
            throw failure;
        }
        return recordKnownOutcome(workItem, runningRowVersion, result);
    }

    private WorkerOutcome recordKnownOutcome(
            TextGenerationJobWorkItem workItem,
            long runningRowVersion,
            ModelResult<TextGenerationResponse> result) {
        Optional<ModelCallJob> terminalJob;
        try {
            terminalJob = switch (result) {
                case ModelResult.Success(var response) -> modelCallJobRepository
                        .tryRecordTextGenerationSuccess(
                                workItem.jobId(), workItem.userId(), runningRowVersion, response);
                case ModelResult.Failure(var failure) -> modelCallJobRepository
                        .tryRecordFailure(workItem.jobId(), workItem.userId(), runningRowVersion, failure);
            };
        }
        catch (RuntimeException persistenceFailure) {
            // 结果已知但已不可恢复地留在内存，持久化视角只能诚实降级为未知。
            return recordOutcomeUnknownBestEffort(workItem, runningRowVersion, persistenceFailure);
        }
        if (terminalJob.isEmpty()) {
            LOGGER.warn(
                    "model call job terminal write lost version race jobId={} userId={}",
                    workItem.jobId(), workItem.userId());
            return WorkerOutcome.TERMINAL_WRITE_LOST;
        }
        return switch (result) {
            case ModelResult.Success<TextGenerationResponse> ignored -> WorkerOutcome.SUCCEEDED;
            case ModelResult.Failure(var failure) -> failure.kind() == ModelFailureKind.TIMEOUT
                    ? WorkerOutcome.TIMED_OUT
                    : WorkerOutcome.FAILED;
        };
    }

    private WorkerOutcome recordOutcomeUnknownBestEffort(
            TextGenerationJobWorkItem workItem,
            long runningRowVersion,
            Throwable cause) {
        Optional<ModelCallJob> terminalJob;
        try {
            terminalJob = modelCallJobRepository.tryRecordOutcomeUnknown(
                    workItem.jobId(), workItem.userId(), runningRowVersion);
        }
        catch (RuntimeException recordFailure) {
            cause.addSuppressed(recordFailure);
            // Job 将停留在 RUNNING：没有批准的 reconciliation 机制前这是不可归约的终局。
            LOGGER.error(
                    "model call job stuck RUNNING after unknown outcome jobId={} userId={} "
                            + "causeType={} recordFailureType={}",
                    workItem.jobId(),
                    workItem.userId(),
                    cause.getClass().getName(),
                    recordFailure.getClass().getName());
            return WorkerOutcome.OUTCOME_UNKNOWN_UNRECORDED;
        }
        if (terminalJob.isEmpty()) {
            LOGGER.warn(
                    "model call job outcome-unknown write lost version race jobId={} userId={} causeType={}",
                    workItem.jobId(), workItem.userId(), cause.getClass().getName());
            return WorkerOutcome.TERMINAL_WRITE_LOST;
        }
        LOGGER.warn(
                "model call job outcome unknown jobId={} userId={} causeType={}",
                workItem.jobId(), workItem.userId(), cause.getClass().getName());
        return WorkerOutcome.OUTCOME_UNKNOWN;
    }

    public enum WorkerOutcome {
        CLAIM_LOST,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        OUTCOME_UNKNOWN,
        OUTCOME_UNKNOWN_UNRECORDED,
        TERMINAL_WRITE_LOST
    }
}
