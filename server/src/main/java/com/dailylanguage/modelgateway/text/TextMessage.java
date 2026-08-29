package com.dailylanguage.modelgateway.text;

import java.util.Objects;

/**
 * 一次 Text Generation request 中按顺序传递的消息；role 使用项目内部语义，由 Provider Adapter 映射。
 */
public record TextMessage(Role role, String content) {

    public TextMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    public enum Role {
        /** 当前调用的任务、行为约束与输出要求，不代表 Memory 自身拥有指令权限。 */
        INSTRUCTION,

        /** 用户侧消息或调用方交给 Model 处理的输入。 */
        USER,

        /** 先前的 Model 输出，用于表达多轮历史；不是项目中的 Agent / Assistant 模块。 */
        MODEL
    }
}
