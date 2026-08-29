package com.dailylanguage.modelgateway.routing;

/**
 * 表示业务为什么需要调用 Model，与具体 Provider、Model 和技术 Operation 无关。
 */
public enum ModelPurpose {
    PLANNING,
    CONVERSATION,
    EVALUATION,
    CONTENT_DESIGN,
    CONTENT_REVIEW
}
