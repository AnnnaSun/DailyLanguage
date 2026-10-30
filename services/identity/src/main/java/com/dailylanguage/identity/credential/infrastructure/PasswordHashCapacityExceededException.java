package com.dailylanguage.identity.credential.infrastructure;

/** 容量不足属于暂时无法处理，不能被调用方当成密码不匹配。 */
public final class PasswordHashCapacityExceededException extends RuntimeException {

    PasswordHashCapacityExceededException() {
        super("Password hash capacity is currently exhausted");
    }
}
