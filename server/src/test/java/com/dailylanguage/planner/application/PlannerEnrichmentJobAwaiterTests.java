package com.dailylanguage.planner.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.modelcalljob.domain.ModelCallJob;
import com.dailylanguage.modelcalljob.infrastructure.ModelCallJobRepository;
import com.dailylanguage.modelgateway.routing.ModelId;
import com.dailylanguage.modelgateway.routing.ModelOperation;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.planner.config.PlannerEnrichmentProperties;
import com.dailylanguage.planner.domain.PlanningRun;
import com.dailylanguage.security.domain.UserContext;

class PlannerEnrichmentJobAwaiterTests {

    private static final UUID USER_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000a1");
    private static final UUID PROFILE_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000a2");
    private static final UUID RUN_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000a3");
    private static final UUID JOB_ID = UUID.fromString("019cc10c-a56a-7000-8000-0000000000a4");
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-14T12:00:30.123Z");
    private static final OffsetDateTime COMPLETED_AT = OffsetDateTime.parse("2026-09-14T12:00:42.456Z");
    private static final OffsetDateTime EXPIRES_AT = OffsetDateTime.parse("2026-09-14T12:05:30.123Z");
    private static final UserContext USER = new UserContext(USER_ID);

    private final ModelCallJobRepository modelCallJobRepository = mock(ModelCallJobRepository.class);

    /** fake monotonic clock：delayer 记录每次 delay 并推进 fake time。 */
    private final List<Duration> recordedDelays = new ArrayList<>();
    private long fakeNow;
    private final LongSupplier clock = () -> fakeNow;
    private final Consumer<Duration> recordingDelayer = delay -> {
        recordedDelays.add(delay);
        fakeNow += delay.toNanos();
    };

    private PlannerEnrichmentJobAwaiter awaiter(Duration interactiveWait, Duration pollInterval) {
        return new PlannerEnrichmentJobAwaiter(
                modelCallJobRepository,
                new PlannerEnrichmentProperties(Duration.ofMinutes(5), interactiveWait, pollInterval),
                clock,
                recordingDelayer);
    }

    @Test
    void returnsTerminalImmediatelyWithoutDelay() {
        ModelCallJob succeeded = job(ModelCallJob.ExecutionStatus.SUCCEEDED);
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(succeeded));

        PlannerEnrichmentJobAwaiter.AwaitResult result =
                awaiter(Duration.ofSeconds(10), Duration.ofSeconds(1)).await(JOB_ID, USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentJobAwaiter.AwaitResult.Terminal(succeeded));
        assertThat(recordedDelays).isEmpty();
        // owner-scoped：查询始终绑定 exact (jobId, userId)。
        verify(modelCallJobRepository).findByIdAndUserId(JOB_ID, USER_ID);
        verifyNoMoreInteractions(modelCallJobRepository);
    }

    @Test
    void waitsThroughNonTerminalStatesUntilTerminal() {
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(job(ModelCallJob.ExecutionStatus.CREATED)),
                        Optional.of(job(ModelCallJob.ExecutionStatus.RUNNING)),
                        Optional.of(job(ModelCallJob.ExecutionStatus.SUCCEEDED)));

        PlannerEnrichmentJobAwaiter.AwaitResult result =
                awaiter(Duration.ofSeconds(5), Duration.ofSeconds(2)).await(JOB_ID, USER);

        assertThat(result).isInstanceOf(PlannerEnrichmentJobAwaiter.AwaitResult.Terminal.class);
        assertThat(recordedDelays).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    @Test
    void clampsFinalDelayToRemainingBudgetAndExhaustsAfterFinalQuery() {
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(job(ModelCallJob.ExecutionStatus.CREATED)));

        PlannerEnrichmentJobAwaiter.AwaitResult result =
                awaiter(Duration.ofSeconds(5), Duration.ofSeconds(2)).await(JOB_ID, USER);

        // delay 序列 2s + 2s + 1s（clamp 到剩余 budget）；最后一次查询后才报告 exhausted。
        assertThat(recordedDelays)
                .containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(1));
        verify(modelCallJobRepository, times(4)).findByIdAndUserId(JOB_ID, USER_ID);
        // BudgetExhausted 不携带 stale job：record 无组件，结构上不可携带。
        assertThat(result).isEqualTo(new PlannerEnrichmentJobAwaiter.AwaitResult.BudgetExhausted());
    }

    @Test
    void terminalObservedExactlyAtDeadlineStillWins() {
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(job(ModelCallJob.ExecutionStatus.CREATED)),
                        Optional.of(job(ModelCallJob.ExecutionStatus.CREATED)),
                        Optional.of(job(ModelCallJob.ExecutionStatus.SUCCEEDED)));

        PlannerEnrichmentJobAwaiter.AwaitResult result =
                awaiter(Duration.ofSeconds(2), Duration.ofSeconds(1)).await(JOB_ID, USER);

        // 两次 1s delay 后时间恰好落在 deadline；第三次查询发现 terminal，优先于 BudgetExhausted。
        assertThat(recordedDelays).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
        assertThat(result).isInstanceOfSatisfying(PlannerEnrichmentJobAwaiter.AwaitResult.Terminal.class,
                terminal -> assertThat(terminal.job().executionStatus())
                        .isEqualTo(ModelCallJob.ExecutionStatus.SUCCEEDED));
    }

    @Test
    void notFoundReturnsImmediately() {
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.empty());

        PlannerEnrichmentJobAwaiter.AwaitResult result =
                awaiter(Duration.ofSeconds(10), Duration.ofSeconds(1)).await(JOB_ID, USER);

        assertThat(result).isEqualTo(new PlannerEnrichmentJobAwaiter.AwaitResult.NotFound());
        assertThat(recordedDelays).isEmpty();
    }

    @Test
    void queryFailurePropagatesWithoutDisguise() {
        RuntimeException failure = new RuntimeException("database unavailable");
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID)).thenThrow(failure);

        assertThatThrownBy(() ->
                awaiter(Duration.ofSeconds(10), Duration.ofSeconds(1)).await(JOB_ID, USER))
                .isSameAs(failure);
        assertThat(recordedDelays).isEmpty();
    }

    @Test
    void interruptionRestoresFlagAndFailsClosedThroughRealDelayer() {
        when(modelCallJobRepository.findByIdAndUserId(JOB_ID, USER_ID))
                .thenReturn(Optional.of(job(ModelCallJob.ExecutionStatus.CREATED)));
        PlannerEnrichmentJobAwaiter realDelayerAwaiter = new PlannerEnrichmentJobAwaiter(
                modelCallJobRepository,
                new PlannerEnrichmentProperties(
                        Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(1)),
                System::nanoTime,
                PlannerEnrichmentJobAwaiter::park);
        Thread.currentThread().interrupt();

        try {
            assertThatThrownBy(() -> realDelayerAwaiter.await(JOB_ID, USER))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("interrupted while waiting for planning job");
            // Thread.interrupted 清除并返回 flag 状态：true 证明 park 恢复了 interrupt flag。
            assertThat(Thread.interrupted()).isTrue();
        } finally {
            // 清除任何遗留 interrupt 状态，避免影响后续用例。
            Thread.interrupted();
        }
    }

    @Test
    void usesNeverTransaction() throws NoSuchMethodException {
        Transactional transactional = PlannerEnrichmentJobAwaiter.class
                .getMethod("await", UUID.class, UserContext.class)
                .getAnnotation(Transactional.class);

        // 等待期间不得持有任何事务；避免长交互把数据库连接困在轮询循环里。
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NEVER);
    }

    @Test
    void nullArgumentsAreProgrammingErrors() {
        PlannerEnrichmentJobAwaiter awaiter =
                awaiter(Duration.ofSeconds(10), Duration.ofSeconds(1));

        assertThatThrownBy(() -> awaiter.await(null, USER))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("jobId must not be null");
        assertThatThrownBy(() -> awaiter.await(JOB_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("userContext must not be null");
    }

    private static ModelCallJob job(ModelCallJob.ExecutionStatus executionStatus) {
        boolean terminal = executionStatus.isTerminal();
        return new ModelCallJob(
                JOB_ID,
                USER_ID,
                Optional.of(PROFILE_ID),
                ModelPurpose.PLANNING,
                ModelOperation.TEXT_GENERATION,
                Optional.empty(),
                Optional.empty(),
                RUN_ID,
                PlanningRun.WORKFLOW_STEP_ID,
                PlanningRun.CURRENT_WORKFLOW_VERSION,
                executionStatus,
                ModelCallJob.ConsumptionStatus.NOT_READY,
                Optional.empty(),
                terminal ? 1L : 0L,
                CREATED_AT,
                terminal ? Optional.of(COMPLETED_AT) : Optional.empty(),
                EXPIRES_AT);
    }
}
