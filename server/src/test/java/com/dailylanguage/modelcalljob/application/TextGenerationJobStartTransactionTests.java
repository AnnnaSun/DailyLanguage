package com.dailylanguage.modelcalljob.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.dailylanguage.modelcalljob.application.TextGenerationJobStart.StartCommand;
import com.dailylanguage.modelcalljob.application.TextGenerationJobSubmission.SubmissionOutcome;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.domain.NewModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;

class TextGenerationJobStartTransactionTests {

    private final ModelCallJobRepository modelCallJobRepository = mock(ModelCallJobRepository.class);
    private final TextGenerationJobSubmission submission = mock(TextGenerationJobSubmission.class);
    private final TestTransactionManager transactionManager = new TestTransactionManager();
    private final TextGenerationJobStart jobStart = transactionalProxy(
            new TextGenerationJobStart(modelCallJobRepository, submission), transactionManager);

    @Test
    void startsWithoutOpeningATransaction() {
        StartCommand command = command();
        when(modelCallJobRepository.create(any(NewModelCallJob.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return createdJob(command);
        });
        when(submission.submit(any(TextGenerationJobWorkItem.class)))
                .thenReturn(SubmissionOutcome.ACCEPTED);

        jobStart.start(command);
    }

    @Test
    void activeCallerTransactionIsRejectedBeforeJobCreation() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> jobStart.start(command())))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(modelCallJobRepository, submission);
    }

    private static TextGenerationJobStart transactionalProxy(
            TextGenerationJobStart target,
            TestTransactionManager transactionManager) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (TextGenerationJobStart) proxyFactory.getProxy();
    }

    private static StartCommand command() {
        ProviderId providerId = new ProviderId("deepseek");
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.PLANNING,
                List.of(new TextMessage(TextMessage.Role.USER, "Plan today's practice.")),
                TextOutputSpecification.plainText());
        return new StartCommand(
                UUID.randomUUID(),
                Optional.empty(),
                UUID.randomUUID(),
                "GENERATE_TASK",
                0L,
                OffsetDateTime.now().plusHours(1),
                request,
                new TransientProviderCredential(providerId, "not-sent-to-provider"));
    }

    private static ModelCallJob createdJob(StartCommand command) {
        OffsetDateTime createdAt = OffsetDateTime.now();
        return new ModelCallJob(
                UUID.randomUUID(),
                command.userId(),
                command.languageProfileId(),
                command.request().purpose(),
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                command.workflowId(),
                command.workflowStepId(),
                command.workflowVersion(),
                ModelCallJob.ExecutionStatus.CREATED,
                ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(),
                0L,
                createdAt,
                Optional.empty(),
                command.expiresAt());
    }

    private static final class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return TransactionSynchronizationManager.isActualTransactionActive();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // Test transaction 只验证 propagation contract，不需要真实 resource。
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 测试事务不绑定真实 resource，无需提交动作。
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 测试事务不绑定真实 resource，无需回滚动作。
        }
    }
}
