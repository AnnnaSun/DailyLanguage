package com.dailylanguage.evaluator.application;

import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.dailylanguage.evaluator.application.EvaluationDispatchService.DispatchResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.ConsumptionResult;
import com.dailylanguage.evaluator.application.EvaluationResultConsumptionService.DurableOutcome;
import com.dailylanguage.evaluator.domain.EvaluationRun;
import com.dailylanguage.modelgateway.credential.TransientProviderCredential;
import com.dailylanguage.modelgateway.routing.ModelPurpose;
import com.dailylanguage.modelgateway.routing.ProviderId;
import com.dailylanguage.modelgateway.text.execution.FixedTextGenerationRoutes;
import com.dailylanguage.security.domain.UserContext;

/**
 * 组合一次 PracticeSession semantic evaluation 的 HTTP-facing Application flow。外层禁止事务，
 * 以保留 Reader、Run 创建、异步 dispatch 与 result consumption 各自已经定义的事务边界。
 */
@Service
public class PracticeSessionEvaluationService {

    private final GroundedEvaluationInputReader inputReader;
    private final EvaluationDispatchService dispatchService;
    private final EvaluationResultConsumptionService consumptionService;
    private final FixedTextGenerationRoutes routes;

    public PracticeSessionEvaluationService(
            GroundedEvaluationInputReader inputReader,
            EvaluationDispatchService dispatchService,
            EvaluationResultConsumptionService consumptionService,
            FixedTextGenerationRoutes routes) {
        this.inputReader = Objects.requireNonNull(inputReader, "inputReader must not be null");
        this.dispatchService = Objects.requireNonNull(dispatchService, "dispatchService must not be null");
        this.consumptionService = Objects.requireNonNull(
                consumptionService, "consumptionService must not be null");
        this.routes = Objects.requireNonNull(routes, "routes must not be null");
    }

    /** Credential 只在首次 trigger 调用链中使用；重复状态推进应改用 {@link #reconcile}. */
    @Transactional(propagation = Propagation.NEVER)
    public EvaluationResult start(
            UUID languageProfileId,
            UUID sessionId,
            UserContext userContext,
            String providerIdValue,
            String credentialSecret) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");

        GroundedEvaluationInputResult inputResult = inputReader.readOwned(
                languageProfileId, sessionId, userContext);
        if (!(inputResult instanceof GroundedEvaluationInputResult.Ready ready)) {
            return mapInputFailure(inputResult);
        }

        ProviderId providerId;
        try {
            providerId = new ProviderId(providerIdValue);
        }
        catch (NullPointerException | IllegalArgumentException exception) {
            return new EvaluationResult.InvalidProviderId();
        }
        if (credentialSecret == null || credentialSecret.isBlank()) {
            return new EvaluationResult.InvalidProviderCredential();
        }
        var route = routes.findRoute(ModelPurpose.EVALUATION).orElse(null);
        if (route == null) {
            return new EvaluationResult.ConfigurationUnavailable();
        }
        if (!route.providerId().equals(providerId)) {
            return new EvaluationResult.ProviderMismatch();
        }

        DispatchResult dispatchResult = dispatchService.dispatchForReadyInput(
                ready,
                userContext,
                new TransientProviderCredential(route.providerId(), credentialSecret));
        switch (dispatchResult) {
            case DispatchResult.Unavailable ignored -> {
                return new EvaluationResult.ConfigurationUnavailable();
            }
            case DispatchResult.NotFound ignored -> {
                return new EvaluationResult.SessionNotFound();
            }
            case DispatchResult.NotCompleted ignored -> {
                return new EvaluationResult.SessionNotCompleted();
            }
            case DispatchResult.InconsistentInput ignored -> {
                return new EvaluationResult.InputUnavailable();
            }
            case DispatchResult.Created ignored -> {
            }
            case DispatchResult.Existing ignored -> {
            }
        }
        return consumeKnownRun(ready, userContext);
    }

    /** 显式 mutation endpoint 使用该入口推进 durable reconciliation，不读取或接收 Credential。 */
    @Transactional(propagation = Propagation.NEVER)
    public EvaluationResult reconcile(
            UUID languageProfileId, UUID sessionId, UserContext userContext) {
        Objects.requireNonNull(languageProfileId, "languageProfileId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(userContext, "userContext must not be null");

        GroundedEvaluationInputResult inputResult = inputReader.readOwned(
                languageProfileId, sessionId, userContext);
        if (!(inputResult instanceof GroundedEvaluationInputResult.Ready ready)) {
            return mapInputFailure(inputResult);
        }
        ConsumptionResult result = consumptionService.consumeForReadyInput(ready, userContext);
        if (result instanceof ConsumptionResult.NotFound) {
            return new EvaluationResult.EvaluationNotFound();
        }
        return mapKnownRunConsumption(result);
    }

    private EvaluationResult consumeKnownRun(
            GroundedEvaluationInputResult.Ready ready, UserContext userContext) {
        ConsumptionResult result = consumptionService.consumeForReadyInput(ready, userContext);
        if (result instanceof ConsumptionResult.NotFound) {
            throw new IllegalStateException("dispatched evaluation run cannot be found");
        }
        return mapKnownRunConsumption(result);
    }

    private static EvaluationResult mapKnownRunConsumption(ConsumptionResult result) {
        return switch (result) {
            case ConsumptionResult.Pending pending -> new EvaluationResult.Pending(pending.run());
            case ConsumptionResult.Consumed consumed -> new EvaluationResult.Terminal(consumed.outcome());
            case ConsumptionResult.Existing existing -> new EvaluationResult.Terminal(existing.outcome());
            case ConsumptionResult.InconsistentInput ignored ->
                    throw new IllegalStateException("evaluation input no longer matches its durable run");
            case ConsumptionResult.NotFound ignored ->
                    throw new IllegalStateException("evaluation run cannot be found");
        };
    }

    private static EvaluationResult mapInputFailure(GroundedEvaluationInputResult result) {
        return switch (result) {
            case GroundedEvaluationInputResult.NotFound ignored ->
                    new EvaluationResult.SessionNotFound();
            case GroundedEvaluationInputResult.NotCompleted ignored ->
                    new EvaluationResult.SessionNotCompleted();
            case GroundedEvaluationInputResult.MaterialUnavailable ignored ->
                    new EvaluationResult.InputUnavailable();
            case GroundedEvaluationInputResult.InconsistentSnapshot ignored ->
                    new EvaluationResult.InputUnavailable();
            case GroundedEvaluationInputResult.Ready ignored ->
                    throw new IllegalArgumentException("ready input is not a failure");
        };
    }

    public sealed interface EvaluationResult {

        record Pending(EvaluationRun run) implements EvaluationResult {
            public Pending {
                Objects.requireNonNull(run, "run must not be null");
                if (run.status() != EvaluationRun.Status.PENDING) {
                    throw new IllegalArgumentException("pending result requires a PENDING run");
                }
            }
        }

        record Terminal(DurableOutcome outcome) implements EvaluationResult {
            public Terminal {
                Objects.requireNonNull(outcome, "outcome must not be null");
            }
        }

        record InvalidProviderId() implements EvaluationResult {
        }

        record InvalidProviderCredential() implements EvaluationResult {
        }

        record ProviderMismatch() implements EvaluationResult {
        }

        record SessionNotFound() implements EvaluationResult {
        }

        record SessionNotCompleted() implements EvaluationResult {
        }

        record EvaluationNotFound() implements EvaluationResult {
        }

        record InputUnavailable() implements EvaluationResult {
        }

        record ConfigurationUnavailable() implements EvaluationResult {
        }
    }
}
