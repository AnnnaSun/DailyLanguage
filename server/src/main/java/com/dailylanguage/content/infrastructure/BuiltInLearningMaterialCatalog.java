package com.dailylanguage.content.infrastructure;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.dailylanguage.content.domain.AvailableMaterialSummary;
import com.dailylanguage.content.domain.LearningMaterialCatalog;
import com.dailylanguage.content.domain.MaterialIdentity;
import com.dailylanguage.content.domain.MaterialQueryResult;
import com.dailylanguage.content.domain.MaterialUnavailableReason;
import com.dailylanguage.content.domain.PublishedLearningMaterial;
import com.dailylanguage.content.domain.SupportScaffold;

/**
 * 启动期 eager 加载并验证整个 Built-in pack；constructor 抛出异常即拒绝 Spring context 启动（fail closed）。
 */
@Component
public class BuiltInLearningMaterialCatalog implements LearningMaterialCatalog {

    private final List<PublishedLearningMaterial> materialsInPackOrder;
    private final Map<MaterialIdentity, PublishedLearningMaterial> materialsByIdentity;

    public BuiltInLearningMaterialCatalog() {
        this(new ClasspathBuiltInMaterialLoader().load());
    }

    BuiltInLearningMaterialCatalog(List<PublishedLearningMaterial> loadedMaterials) {
        Objects.requireNonNull(loadedMaterials, "loadedMaterials must not be null");
        Map<MaterialIdentity, PublishedLearningMaterial> byIdentity = new LinkedHashMap<>();
        for (PublishedLearningMaterial material : loadedMaterials) {
            PublishedLearningMaterial existing = byIdentity.putIfAbsent(material.identity(), material);
            if (existing != null) {
                throw new BuiltInMaterialValidationException(
                        "duplicate material identity in built-in pack: " + material.identity());
            }
        }
        this.materialsInPackOrder = List.copyOf(loadedMaterials);
        this.materialsByIdentity = Map.copyOf(byIdentity);
    }

    @Override
    public MaterialQueryResult findByIdentity(MaterialIdentity identity, String supportLanguage) {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(supportLanguage, "supportLanguage must not be null");
        PublishedLearningMaterial material = materialsByIdentity.get(identity);
        if (material == null) {
            return new MaterialQueryResult.Unavailable(MaterialUnavailableReason.MATERIAL_NOT_PUBLISHED);
        }
        return material.supportScaffolds().stream()
                .filter(scaffold -> scaffold.supportLanguage().equals(supportLanguage))
                .findFirst()
                .<MaterialQueryResult>map(
                        scaffold -> new MaterialQueryResult.Available(material, scaffold))
                .orElseGet(() -> new MaterialQueryResult.Unavailable(
                        MaterialUnavailableReason.SUPPORT_LANGUAGE_NOT_PUBLISHED));
    }

    @Override
    public List<AvailableMaterialSummary> listAvailable(String targetLanguage, String supportLanguage) {
        Objects.requireNonNull(targetLanguage, "targetLanguage must not be null");
        Objects.requireNonNull(supportLanguage, "supportLanguage must not be null");
        // 按 materialId 排序：列表顺序与 manifest 编辑顺序无关，Planner 的候选 / fallback 顺序可重放。
        return materialsInPackOrder.stream()
                .filter(material -> material.targetCore().targetLanguage().equals(targetLanguage))
                .filter(material -> material.supportScaffolds().stream()
                        .anyMatch(scaffold -> scaffold.supportLanguage().equals(supportLanguage)))
                .sorted(Comparator.comparing(material -> material.identity().materialId()))
                .map(material -> new AvailableMaterialSummary(
                        material.identity(),
                        material.targetCore().targetLanguage(),
                        material.targetCore().difficulty(),
                        material.targetCore().scenario(),
                        material.supportScaffolds().stream()
                                .map(SupportScaffold::supportLanguage)
                                .sorted()
                                .toList()))
                .toList();
    }
}
