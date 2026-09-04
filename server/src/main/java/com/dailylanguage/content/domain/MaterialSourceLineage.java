package com.dailylanguage.content.domain;

/**
 * published material 的 source lineage，由 manifest 声明、loader 验证后随材料暴露；
 * contentHash 是 loader 重算并核对过的 sha256，保证 lineage 与 artifact 字节一致。
 */
public record MaterialSourceLineage(String source, String sourceVersion, String license, String contentHash) {
}
