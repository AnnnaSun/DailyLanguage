package com.dailylanguage.modelgateway.trace;

/** 将安全的 Model call metadata 写入具体 observability boundary。 */
@FunctionalInterface
public interface ModelCallTraceRecorder {

    void record(ModelCallTrace trace);
}
