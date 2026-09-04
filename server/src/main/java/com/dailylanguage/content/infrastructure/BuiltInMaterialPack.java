package com.dailylanguage.content.infrastructure;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.PublishedLearningMaterial;

/**
 * Built-in loader 的完整结果：保留全部 immutable published versions 供历史 identity 查询，同时单独声明
 * 当前允许进入 Planner candidate list 的版本。
 */
public record BuiltInMaterialPack(
        List<PublishedLearningMaterial> materials,
        Set<MaterialIdentity> plannableMaterialIdentities) {

    public BuiltInMaterialPack {
        materials = List.copyOf(Objects.requireNonNull(materials, "materials must not be null"));
        plannableMaterialIdentities = Set.copyOf(Objects.requireNonNull(
                plannableMaterialIdentities, "plannableMaterialIdentities must not be null"));
        Set<MaterialIdentity> loadedIdentities = materials.stream()
                .map(PublishedLearningMaterial::identity)
                .collect(Collectors.toUnmodifiableSet());
        if (!loadedIdentities.containsAll(plannableMaterialIdentities)) {
            throw new IllegalArgumentException("every plannable material identity must exist in materials");
        }
        Set<String> plannableMaterialIds = new HashSet<>();
        for (MaterialIdentity identity : plannableMaterialIdentities) {
            if (!plannableMaterialIds.add(identity.materialId())) {
                throw new IllegalArgumentException(
                        "only one version per materialId may be plannable: " + identity.materialId());
            }
        }
    }
}
