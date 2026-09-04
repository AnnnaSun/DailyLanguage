package com.dailylanguage.content.domain;

/**
 * catalog 查询结果：Available 携带完整材料与按请求语言选中的 scaffold；Unavailable 只区分两类发布缺失，
 * 调用方不得把 unavailable 当作跨语言借用或 live 翻译的理由。
 */
public sealed interface MaterialQueryResult {

    record Available(PublishedLearningMaterial material, SupportScaffold selectedScaffold)
            implements MaterialQueryResult {
    }

    record Unavailable(MaterialUnavailableReason reason) implements MaterialQueryResult {
    }
}
