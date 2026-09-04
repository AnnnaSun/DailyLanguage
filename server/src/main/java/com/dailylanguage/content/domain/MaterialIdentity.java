package com.dailylanguage.content.domain;

/**
 * 一份 immutable published material 的稳定标识，是 catalog 查询与后续 LearningTask 引用的键；
 * content / rubric / lineage 的任何修改都必须以新 publishedVersion 发布，不能原地变更。
 */
public record MaterialIdentity(String materialId, String publishedVersion) {
}
