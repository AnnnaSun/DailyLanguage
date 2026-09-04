package com.dailylanguage.content.infrastructure;

import java.util.List;

import com.dailylanguage.content.domain.SupportScaffold;
import com.dailylanguage.content.domain.TargetPracticeCore;

/**
 * 单个 material artifact 文件顶层的绑定 record（materialId / publishedVersion 平铺在顶层）；
 * loader 校验其与 manifest Entry 一致后，组装成 domain 的 PublishedLearningMaterial 并附上 lineage。
 */
public record BuiltInMaterialArtifact(
        String materialId,
        String publishedVersion,
        TargetPracticeCore targetCore,
        List<SupportScaffold> supportScaffolds) {
}
