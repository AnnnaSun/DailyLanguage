package com.dailylanguage.modelcalljob.application;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Text Generation Job 的进程内提交边界；Application caller 不直接依赖 Spring TaskExecutor，
 * 后续若采用 durable queue，execution infrastructure 的选择优先收敛在该边界内。
 */
@Component
public class TextGenerationJobSubmission {

    private final Executor modelCallJobTaskExecutor;
    private final TextGenerationJobWorker worker;

    public TextGenerationJobSubmission(
            @Qualifier("modelCallJobTaskExecutor") Executor modelCallJobTaskExecutor,
            TextGenerationJobWorker worker) {
        this.modelCallJobTaskExecutor = Objects.requireNonNull(
                modelCallJobTaskExecutor, "modelCallJobTaskExecutor must not be null");
        this.worker = Objects.requireNonNull(worker, "worker must not be null");
    }

    /**
     * ACCEPTED 只表示当前进程的 execution boundary 已接纳任务，不代表 Provider 调用或 Job 已完成。
     */
    public SubmissionOutcome submit(TextGenerationJobWorkItem workItem) {
        Objects.requireNonNull(workItem, "workItem must not be null");
        try {
            modelCallJobTaskExecutor.execute(() -> worker.execute(workItem));
            return SubmissionOutcome.ACCEPTED;
        }
        catch (RejectedExecutionException rejection) {
            return SubmissionOutcome.CAPACITY_UNAVAILABLE;
        }
    }

    public enum SubmissionOutcome {
        ACCEPTED,
        CAPACITY_UNAVAILABLE
    }
}
