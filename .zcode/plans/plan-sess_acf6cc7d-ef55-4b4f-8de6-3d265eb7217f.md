# M0-S9I 实现计划 — Dedicated ModelCallJob TaskExecutor Boundary

已核对：分支 `codex/M0S9` / HEAD `de906e9`，与 handoff 一致；你的指示构成 S9I scope 批准。Plan mode 激活前已完成第 1 个文件，其余步骤如下。

## 生产代码（4 个文件，总 LOC 预算 <= 150，实际约 90）

1. **已完成** — `server/src/main/java/com/dailylanguage/modelcalljob/infrastructure/ModelCallJobExecutionProperties.java`
   `@ConfigurationProperties("app.model-call-job.execution")` record，嵌套 `ExecutorSettings(workers, queueCapacity)`，compact constructor 校验两者必须为正（风格对齐 `TextGenerationGatewayProperties`）。

2. 新建 `ModelCallJobExecutionConfiguration.java`（同包）
   - `@Configuration(proxyBeanMethods = false)` + `@EnableConfigurationProperties`；
   - 包可见常量 `MODEL_CALL_JOB_TASK_EXECUTOR = "modelCallJobTaskExecutor"`；
   - bean 返回 `ThreadPoolTaskExecutor`：core=max=workers（固定池）、queueCapacity（有界队列）、threadNamePrefix `model-call-job-`、`ThreadPoolExecutor.AbortPolicy()`（容量耗尽显式抛 `RejectedExecutionException` 给提交方，不用 CallerRuns、不静默丢弃）；
   - 不注入/不包装/不复用 Gateway `modelCallExecutor`；简短 WHY 注释说明线程池隔离与 Abort 选择。

3. 新建 `server/src/main/resources/model-call-job.yml`
   ```yaml
   app:
     model-call-job:
       execution:
         executor:
           workers: ${MODEL_CALL_JOB_EXECUTOR_WORKERS:4}
           queue-capacity: ${MODEL_CALL_JOB_EXECUTOR_QUEUE_CAPACITY:16}
   ```

4. 修改 `application.yml`：`spring.config.import` 改为列表，在 `model-gateway.yml` 旁追加 `classpath:model-call-job.yml`。

## 测试（2 个新文件，均无 DB 依赖）

5. `ModelCallJobExecutionPropertiesTests.java`（纯单测）：正数设置通过；null executor、workers=0、queueCapacity=0 拒绝并断言异常消息。

6. `ModelCallJobExecutionConfigurationTests.java`（`ApplicationContextRunner` + `ConfigDataApplicationContextInitializer`，与 Gateway 测试同风格）：
   - 默认绑定 4 workers / 16 queue / 前缀正确；context 关闭后 executor 已 shutdown；
   - 覆盖绑定 workers=2 / queue-capacity=3 生效；
   - 任务线程名以 `model-call-job-` 开头且为 platform thread（非 virtual）；
   - 容量拒绝：workers=1/queue=1 占满后第三次提交抛 `RejectedExecutionException`；
   - 注册名为 `modelCallExecutor` 的 stand-in 测试 bean，断言 Job executor 与之是不同 bean 实例（不共享身份）；
   - workers=0 阻止 context 启动。

## 验证（按 handoff verification plan）

- `./mvnw test -Dtest='ModelCallJobExecutionPropertiesTests,ModelCallJobExecutionConfigurationTests,TextGenerationGatewayConfigurationTests'`
- `./mvnw -q compile`
- 无数据库测试（S9I 无持久化变化）；不跑 full suite，除非 targeted 证据暴露共享 Spring composition 风险。

## 边界（handoff Explicit Non-scope 不变）

无 Job 提交 API / worker / Runnable、无 `CREATED->RUNNING` 运行时编排、无 `TextGenerationPort` 调用、无 Credential 传播、无 interactive wait / HTTP 轮询、无 `OUTCOME_UNKNOWN` / 重启恢复 / graceful-shutdown 语义、无 schema / Repository / Gateway / 学习状态修改。完成后停在 `M0-S9I / REVIEW_PENDING`，commit 由用户决定。