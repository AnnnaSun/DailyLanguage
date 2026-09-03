package com.dailylanguage.content.domain;

import java.util.List;

/**
 * listAvailable 返回的摘要视图，只携带 Planner 规划所需的元数据，不暴露完整 content / scaffold 文本。
 */
public record AvailableMaterialSummary(
        MaterialIdentity identity,
        String targetLanguage,
        MaterialDifficulty difficulty,
        String scenario,
        List<String> supportLanguages) {
}
