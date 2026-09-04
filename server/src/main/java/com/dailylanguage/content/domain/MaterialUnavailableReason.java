package com.dailylanguage.content.domain;

/**
 * MATERIAL_NOT_PUBLISHED：identity 不存在（包括 target language 尚未发布）；
 * SUPPORT_LANGUAGE_NOT_PUBLISHED：材料存在但缺少请求语言的 SupportScaffold。
 */
public enum MaterialUnavailableReason {
    MATERIAL_NOT_PUBLISHED,
    SUPPORT_LANGUAGE_NOT_PUBLISHED
}
