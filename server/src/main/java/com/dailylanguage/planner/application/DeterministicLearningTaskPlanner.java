package com.dailylanguage.planner.application;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.dailylanguage.planner.domain.PlanningCandidateSet;
import com.dailylanguage.planner.domain.PlanningCandidateSetResult;
import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;

/**
 * Provider-free Planner core：委托 {@link EligibleLearningTaskCandidateReader} 产生 deterministic
 * candidate set，再把 first fallback 或 typed unavailable 原因映射回既有 PlanningResult contract。
 * 本身不直接读取 Catalog，也不生成 Content、调用 Model、写数据库或修改 learner state。
 */
@Service
public final class DeterministicLearningTaskPlanner implements LearningTaskPlanner {

    private final EligibleLearningTaskCandidateReader candidateReader;

    public DeterministicLearningTaskPlanner(EligibleLearningTaskCandidateReader candidateReader) {
        this.candidateReader = Objects.requireNonNull(candidateReader, "candidateReader must not be null");
    }

    @Override
    public PlanningResult plan(PlanningRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        PlanningCandidateSetResult candidateResult = candidateReader.readCandidates(request);
        if (candidateResult instanceof PlanningCandidateSetResult.Unavailable unavailable) {
            return new PlanningResult.Unavailable(unavailable.reason());
        }
        PlanningCandidateSet candidateSet = ((PlanningCandidateSetResult.Available) candidateResult).candidateSet();
        return new PlanningResult.Planned(candidateSet.deterministicFallback());
    }
}
