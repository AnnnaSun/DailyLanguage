package com.dailylanguage.authentication;

public final class PasswordHashCapacityExceededException extends RuntimeException {

    PasswordHashCapacityExceededException() {
        super("Password hash capacity is currently exhausted");
    }
}
