package com.dailylanguage.content.domain;

import java.util.List;

/**
 * Content 对 Planner / Practice 暴露的最窄 read boundary。消费方不感知 classpath、JSON 或未来的
 * published storage；findByIdentity 可按完整 identity 解析历史保留版本，listAvailable 只暴露当前可规划版本。
 * 两种查询都按 language pair 精确匹配，缺少 material 或 scaffold 时返回 unavailable，不做 cross-language fallback。
 */
public interface LearningMaterialCatalog {

    MaterialQueryResult findByIdentity(MaterialIdentity identity, String supportLanguage);

    List<AvailableMaterialSummary> listAvailable(String targetLanguage, String supportLanguage);
}
