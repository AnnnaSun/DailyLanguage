package com.dailylanguage.content.infrastructure;

import java.util.List;

/**
 * classpath Built-in pack 索引（manifest.json）的绑定 record；packId 标识一次发布的 pack，
 * materials 中每个 Entry 声明一份材料的 identity、language pair、source lineage 与 artifact 位置 / hash。
 */
public record BuiltInMaterialManifest(int manifestVersion, String packId, List<Entry> materials) {

    /** 单个 material 的 manifest 声明；与 artifact 文件内容的一致性由 loader 逐项核对。 */
    public record Entry(
            String materialId,
            String publishedVersion,
            String targetLanguage,
            List<String> supportLanguages,
            String source,
            String sourceVersion,
            String license,
            String resource,
            String contentHash) {
    }
}
