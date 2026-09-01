package com.dailylanguage.modelgateway.text;

/**
 * 调用方要求的 Text output shape；使用 typed boundary，避免通过 Provider-specific option Map 扩展。
 */
public sealed interface TextOutputSpecification permits
        TextOutputSpecification.PlainText,
        TextOutputSpecification.JsonObject {

    static TextOutputSpecification plainText() {
        return PlainText.INSTANCE;
    }

    static TextOutputSpecification jsonObject() {
        return JsonObject.INSTANCE;
    }

    /** 不要求 schema 的普通文本输出。 */
    enum PlainText implements TextOutputSpecification {
        INSTANCE
    }

    /** 要求 Provider 返回 JSON object；具体 schema 与业务验证不属于本 transport contract。 */
    enum JsonObject implements TextOutputSpecification {
        INSTANCE
    }
}
