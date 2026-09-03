package com.dailylanguage.modelcalljob.config;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Model Call Job worker 的专用执行边界；只提供有界 TaskExecutor，不包含 Job 提交或运行逻辑。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelCallJobExecutionProperties.class)
public class ModelCallJobExecutionConfiguration {

    static final String MODEL_CALL_JOB_TASK_EXECUTOR = "modelCallJobTaskExecutor";

    // Job worker 之后会阻塞等待 Gateway executor 完成 Provider 调用，因此必须与 modelCallExecutor 隔离；
    // Abort 让容量耗尽显式抛给提交方，避免 CallerRuns 把长 Model 生命周期压到提交线程。
    @Bean(name = MODEL_CALL_JOB_TASK_EXECUTOR)
    ThreadPoolTaskExecutor modelCallJobTaskExecutor(ModelCallJobExecutionProperties properties) {
        ModelCallJobExecutionProperties.ExecutorSettings executor = properties.executor();
        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(executor.workers());
        taskExecutor.setMaxPoolSize(executor.workers());
        taskExecutor.setQueueCapacity(executor.queueCapacity());
        taskExecutor.setThreadNamePrefix("model-call-job-");
        taskExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 优雅关闭给 in-flight worker 完整窗口写入终态；等待上限 ≥ 最大 route timeout（30s）+ 落库余量。
        // 硬杀进程仍可能留下 RUNNING Job，其 reconciliation 属于未批准的后续可靠性 scope。
        taskExecutor.setWaitForTasksToCompleteOnShutdown(true);
        taskExecutor.setAwaitTerminationMillis(35_000L);
        return taskExecutor;
    }
}
