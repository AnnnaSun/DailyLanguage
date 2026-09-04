package com.dailylanguage.content.infrastructure;

/**
 * Built-in pack 校验失败的 fail-closed 信号；消息必须携带文件 / entry / 字段级上下文。
 * 由启动期 eager 加载抛出时，直接导致 Spring context 拒绝启动。
 */
public class BuiltInMaterialValidationException extends RuntimeException {

    public BuiltInMaterialValidationException(String message) {
        super(message);
    }

    public BuiltInMaterialValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
