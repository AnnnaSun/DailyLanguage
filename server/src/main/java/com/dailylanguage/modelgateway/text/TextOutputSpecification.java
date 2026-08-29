package com.dailylanguage.modelgateway.text;

/**
 * 调用方要求的 Text output shape；使用 typed boundary，避免通过 Provider-specific option Map 扩展。
 */
public sealed interface TextOutputSpecification permits TextOutputSpecification.PlainText {

    static TextOutputSpecification plainText() {
        return PlainText.INSTANCE;
    }

    /** 不要求 schema 的普通文本输出。 */
    enum PlainText implements TextOutputSpecification {
        INSTANCE
    }
}
