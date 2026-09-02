package com.dailylanguage.modelcalljob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ModelCallJobExecutionConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(ModelCallJobExecutionConfiguration.class);

    @Test
    void bindsDefaultBoundedJobTaskExecutor() {
        AtomicReference<ThreadPoolExecutor> delegateReference = new AtomicReference<>();

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                    ModelCallJobExecutionConfiguration.MODEL_CALL_JOB_TASK_EXECUTOR,
                    ThreadPoolTaskExecutor.class);
            delegateReference.set(executor.getThreadPoolExecutor());
            assertThat(executor.getThreadNamePrefix()).isEqualTo("model-call-job-");
            assertThat(delegateReference.get().getCorePoolSize()).isEqualTo(4);
            assertThat(delegateReference.get().getMaximumPoolSize()).isEqualTo(4);
            assertThat(delegateReference.get().getQueue().remainingCapacity()).isEqualTo(16);
        });

        assertThat(delegateReference.get()).isNotNull();
        assertThat(delegateReference.get().isShutdown()).isTrue();
    }

    @Test
    void overridesProvisionalCapacityThroughDedicatedSettings() {
        contextRunner
                .withPropertyValues(
                        "app.model-call-job.execution.executor.workers=2",
                        "app.model-call-job.execution.executor.queue-capacity=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolExecutor delegate = context.getBean(
                                    ModelCallJobExecutionConfiguration.MODEL_CALL_JOB_TASK_EXECUTOR,
                                    ThreadPoolTaskExecutor.class)
                            .getThreadPoolExecutor();
                    assertThat(delegate.getCorePoolSize()).isEqualTo(2);
                    assertThat(delegate.getMaximumPoolSize()).isEqualTo(2);
                    assertThat(delegate.getQueue().remainingCapacity()).isEqualTo(3);
                });
    }

    @Test
    void gracefulShutdownLetsInFlightTaskFinishBeforeCloseReturns() {
        CountDownLatch taskFinished = new CountDownLatch(1);

        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(
                    ModelCallJobExecutionConfiguration.MODEL_CALL_JOB_TASK_EXECUTOR,
                    ThreadPoolTaskExecutor.class);
            executor.execute(() -> {
                try {
                    Thread.sleep(200L);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                taskFinished.countDown();
            });
        });

        // Spring 6.2 未暴露 waitForTasksToCompleteOnShutdown getter；
        // 若未开启优雅关闭，close 会中断 in-flight 任务且 latch 永不触发。
        assertThat(taskFinished.getCount()).isZero();
    }

    @Test
    void runsTasksOnDedicatedPlatformJobThreads() {
        CountDownLatch taskFinished = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();

        contextRunner.run(context -> {
            ThreadPoolTaskExecutor executor = context.getBean(
                    ModelCallJobExecutionConfiguration.MODEL_CALL_JOB_TASK_EXECUTOR,
                    ThreadPoolTaskExecutor.class);
            executor.execute(() -> {
                workerThread.set(Thread.currentThread());
                taskFinished.countDown();
            });
            assertThat(taskFinished.await(5, TimeUnit.SECONDS)).isTrue();
        });

        assertThat(workerThread.get()).isNotNull();
        assertThat(workerThread.get().getName()).startsWith("model-call-job-");
        assertThat(workerThread.get().isVirtual()).isFalse();
    }

    @Test
    void boundedExecutorRejectsWorkWhenWorkerAndQueueAreOccupied() {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        contextRunner
                .withPropertyValues(
                        "app.model-call-job.execution.executor.workers=1",
                        "app.model-call-job.execution.executor.queue-capacity=1")
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean(
                            ModelCallJobExecutionConfiguration.MODEL_CALL_JOB_TASK_EXECUTOR,
                            ThreadPoolTaskExecutor.class);
                    executor.execute(() -> {
                        workerStarted.countDown();
                        await(releaseWorker);
                    });
                    assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
                    executor.execute(() -> { });

                    assertThatThrownBy(() -> executor.execute(() -> { }))
                            .isInstanceOf(RejectedExecutionException.class);
                    // 先放行 worker 再让 context 关闭，避免 graceful shutdown 等满默认超时。
                    releaseWorker.countDown();
                });
    }

    @Test
    void staysIndependentFromGatewayModelCallExecutorBean() {
        contextRunner
                .withBean("modelCallExecutor", ExecutorService.class,
                        () -> Executors.newSingleThreadExecutor(runnable -> {
                            Thread thread = new Thread(runnable, "model-call-0");
                            thread.setDaemon(true);
                            return thread;
                        }))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ThreadPoolTaskExecutor jobExecutor = context.getBean(
                            ModelCallJobExecutionConfiguration.MODEL_CALL_JOB_TASK_EXECUTOR,
                            ThreadPoolTaskExecutor.class);
                    ExecutorService gatewayExecutor = context.getBean(
                            "modelCallExecutor", ExecutorService.class);

                    assertThat(jobExecutor).isNotSameAs(gatewayExecutor);
                    assertThat(gatewayExecutor).isEqualTo(
                            context.getBean("modelCallExecutor", ExecutorService.class));
                });
    }

    @Test
    void nonPositiveCapacityPreventsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "app.model-call-job.execution.executor.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
