package com.dailylanguage.modelgateway.routing;

/**
 * 表示一次 Model 调用的技术形态；枚举常量不代表对应 Typed Port 已进入当前实现。
 */
public enum ModelOperation {
    TEXT_GENERATION,
    VISION_UNDERSTANDING,
    SPEECH_TRANSCRIPTION,
    SPEECH_SYNTHESIS,
    IMAGE_GENERATION,
    EMBEDDING
}
