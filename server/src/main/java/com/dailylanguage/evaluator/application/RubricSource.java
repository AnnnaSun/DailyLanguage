package com.dailylanguage.evaluator.application;

import java.util.Optional;

import com.dailylanguage.evaluator.domain.SemanticEvaluationRubric;

/** rubric resource 的读取 seam；empty 表示 RUBRIC_UNAVAILABLE（fail closed）。 */
@FunctionalInterface
public interface RubricSource {

    Optional<SemanticEvaluationRubric> resolve(String rubricReference, String targetLanguage);
}
