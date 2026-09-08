package com.dailylanguage.modelcalljob.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

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

import com.dailylanguage.modelcalljob.application.TextGenerationJobDispatch.DispatchCommand;
import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.TextGenerationRequest;
import com.dailylanguage.modelgateway.text.TextMessage;
import com.dailylanguage.modelgateway.text.TextOutputSpecification;

class TextGenerationJobDispatchTransactionTests {

    private final ModelCallJobRepository repository = mock(ModelCallJobRepository.class);
    private final TextGenerationJobSubmission submission = mock(TextGenerationJobSubmission.class);
    private final TestTransactionManager transactionManager = new TestTransactionManager();
    private final TextGenerationJobDispatch dispatch = transactionalProxy(
            new TextGenerationJobDispatch(repository, submission), transactionManager);

    @Test
    void activeCallerTransactionIsRejectedBeforeSubmission() {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(
                ignored -> dispatch.dispatchCreated(command())))
                .isInstanceOf(IllegalTransactionStateException.class);
        verifyNoInteractions(repository, submission);
    }

    private static TextGenerationJobDispatch transactionalProxy(
            TextGenerationJobDispatch target,
            TestTransactionManager transactionManager) {
        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        return (TextGenerationJobDispatch) proxyFactory.getProxy();
    }

    private static DispatchCommand command() {
        OffsetDateTime createdAt = OffsetDateTime.now();
        ModelCallJob job = new ModelCallJob(
                UUID.randomUUID(), UUID.randomUUID(), Optional.of(UUID.randomUUID()),
                ModelPurpose.EVALUATION, ModelOperation.TEXT_GENERATION,
                Optional.empty(), Optional.empty(), UUID.randomUUID(), "SEMANTIC_EVALUATION", 0L,
                ModelCallJob.ExecutionStatus.CREATED, ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(), 0L, createdAt, Optional.empty(), createdAt.plusDays(7));
        TextGenerationRequest request = new TextGenerationRequest(
                ModelPurpose.EVALUATION,
                List.of(new TextMessage(TextMessage.Role.USER, "evaluate")),
                TextOutputSpecification.jsonObject());
        return new DispatchCommand(
                job, request, new TransientProviderCredential(new ProviderId("deepseek"), "secret"));
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
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
