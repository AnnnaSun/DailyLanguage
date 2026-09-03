package com.dailylanguage.planner.application;

import com.dailylanguage.planner.domain.PlanningRequest;
import com.dailylanguage.planner.domain.PlanningResult;

/**
 * Learning Application 对 Planner 的稳定调用边界。M1-S9 可以在不改变调用方 contract 的前提下组合
 * optional model enrichment，但任何实现都必须保留 Java validation 与 deterministic fallback。
 */
public interface LearningTaskPlanner {

    PlanningResult plan(PlanningRequest request);
}
