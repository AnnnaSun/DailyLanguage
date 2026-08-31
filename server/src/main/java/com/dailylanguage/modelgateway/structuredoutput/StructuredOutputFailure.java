package com.dailylanguage.modelgateway.structuredoutput;

/**
 * Model 已成功返回文本后，Java 侧 Structured Output validation 的安全失败分类。
 * 该分类不属于 Provider operational failure，也不携带 generated text 或底层 exception。
 */
public enum StructuredOutputFailure {
    /** 输出不是一个可完整解析的 JSON document。 */
    MALFORMED_JSON,

    /** JSON 不是 object，或不能严格绑定为调用方声明的 Java record。 */
    SHAPE_INVALID,

    /** JSON 中的 enum token 不属于 Java contract 允许的值。 */
    ENUM_INVALID,

    /** 已完成 typed binding，但不满足调用方声明的 deterministic business rule。 */
    SEMANTIC_INVALID
}
