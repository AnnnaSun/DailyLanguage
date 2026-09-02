package com.dailylanguage.modelcalljob.application;

import java.util.Objects;
import java.util.UUID;

import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;

/**
 * 一次 Job 执行的内存工作单元：请求内容与 transient Credential 不进入持久化状态，
 * 只在提交方与 worker 之间传递，随调用结束一起消失。
 */
public record TextGenerationJobWorkItem(
        UUID jobId,
        UUID userId,
        long expectedRowVersion,
        TextGenerationRequest request,
        TransientProviderCredential credential) {

    public TextGenerationJobWorkItem {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        if (expectedRowVersion < 0) {
            throw new IllegalArgumentException("expectedRowVersion must not be negative");
        }
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
    }
}
