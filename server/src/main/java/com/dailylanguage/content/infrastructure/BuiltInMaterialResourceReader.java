package com.dailylanguage.content.infrastructure;

/**
 * Built-in pack 的物理资源读取 seam；M1 由 classpath 实现承载，测试以内存 reader 提供 fail-closed fixture。
 */
@FunctionalInterface
interface BuiltInMaterialResourceReader {

    byte[] read(String relativeLocation);
}
