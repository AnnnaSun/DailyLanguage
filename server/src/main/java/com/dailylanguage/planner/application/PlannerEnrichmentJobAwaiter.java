package com.dailylanguage.planner.application;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.planner.config.PlannerEnrichmentProperties;
import com.dailylanguage.security.domain.UserContext;

/**
 * 只读、owner-scoped 的 bounded awaiter：在 interactiveWait 预算内按 pollInterval 轮询
 * durable ModelCallJob，只报告 terminal、budget exhausted 或 not found。不执行任何
 * Workflow 决策，不消费 result、不取消 Worker、不修改 Job。wait budget 与 Gateway
 * execution timeout 语义分离：budget 到期只表示本次交互等待放弃，不推断 Provider 调用成败。
 */
@Component
public class PlannerEnrichmentJobAwaiter {

    private final ModelCallJobRepository modelCallJobRepository;
    private final Duration interactiveWait;
    private final Duration pollInterval;
    private final LongSupplier nanoTime;
    private final Consumer<Duration> delayer;

    @Autowired
    public PlannerEnrichmentJobAwaiter(
            ModelCallJobRepository modelCallJobRepository,
            PlannerEnrichmentProperties properties) {
        this(modelCallJobRepository, properties, System::nanoTime,
                PlannerEnrichmentJobAwaiter::park);
    }

    /** 测试 seam：注入 monotonic clock 与 delay，不新增顶层 Interface。 */
    PlannerEnrichmentJobAwaiter(
            ModelCallJobRepository modelCallJobRepository,
            PlannerEnrichmentProperties properties,
            LongSupplier nanoTime,
            Consumer<Duration> delayer) {
        this.modelCallJobRepository =
                Objects.requireNonNull(modelCallJobRepository, "modelCallJobRepository must not be null");
        Objects.requireNonNull(properties, "properties must not be null");
        this.interactiveWait = properties.interactiveWait();
        this.pollInterval = properties.pollInterval();
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        this.delayer = Objects.requireNonNull(delayer, "delayer must not be null");
    }

    /**
     * 外层禁止事务：等待期间不持有任何数据库事务，每次轮询都是独立的 owner-scoped 查询。
     * 查询异常原样 fail closed，不伪装成 timeout；interruption 恢复 interrupt flag 后抛出。
     * 最后一次 delay 被 clamp 到剩余 budget，deadline 后的最后一次查询若已 terminal 仍优先返回。
     */
    @Transactional(propagation = Propagation.NEVER)
    public AwaitResult await(UUID jobId, UserContext userContext) {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");
        UUID userId = userContext.userId();
        long deadlineNanos = nanoTime.getAsLong() + interactiveWait.toNanos();
        while (true) {
            Optional<ModelCallJob> job = modelCallJobRepository.findByIdAndUserId(jobId, userId);
            if (job.isEmpty()) {
                return new AwaitResult.NotFound();
            }
            ModelCallJob current = job.orElseThrow();
            if (current.executionStatus().isTerminal()) {
                return new AwaitResult.Terminal(current);
            }
            long remainingNanos = deadlineNanos - nanoTime.getAsLong();
            if (remainingNanos <= 0) {
                // 不携带可供后续决策信任的 stale job；S9E2 的裁决另行读取 durable 状态。
                return new AwaitResult.BudgetExhausted();
            }
            delayer.accept(Duration.ofNanos(Math.min(pollInterval.toNanos(), remainingNanos)));
        }
    }

    /** interruption 恢复 interrupt flag 并 fail closed；不被伪装成 timeout。 */
    static void park(Duration delay) {
        try {
            Thread.sleep(delay.toMillis(), (int) (delay.toNanos() % 1_000_000L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for planning job", exception);
        }
    }

    public sealed interface AwaitResult {

        record Terminal(ModelCallJob job) implements AwaitResult {

            public Terminal {
                Objects.requireNonNull(job, "job must not be null");
            }
        }

        /** 等待预算耗尽；不构成任何 Workflow 决策输入。 */
        record BudgetExhausted() implements AwaitResult {
        }

        /** owner 范围内不存在该 Job。 */
        record NotFound() implements AwaitResult {
        }
    }
}
