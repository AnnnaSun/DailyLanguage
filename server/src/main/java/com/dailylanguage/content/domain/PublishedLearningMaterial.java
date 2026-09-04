package com.dailylanguage.content.domain;

import java.util.List;

/**
 * 用户实际看到的完整 published content：immutable artifact + 稳定 materialId + publishedVersion；
 * 修改任何 content / rubric / lineage 必须发布新 version，不能原地覆盖。
 */
public record PublishedLearningMaterial(
        MaterialIdentity identity,
        TargetPracticeCore targetCore,
        List<SupportScaffold> supportScaffolds,
        MaterialSourceLineage sourceLineage) {

    public PublishedLearningMaterial {
        supportScaffolds = supportScaffolds == null ? null : List.copyOf(supportScaffolds);
    }
}
