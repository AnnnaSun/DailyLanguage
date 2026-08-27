package com.dailylanguage.authentication.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 将是否开放注册的配置收敛为 registration API 可以直接执行的能力边界。
 */
@Component
public final class RegistrationCapability {

    private final State state;

    public RegistrationCapability(@Value("${app.registration-enabled}") boolean registrationEnabled) {
        this.state = registrationEnabled ? State.PUBLIC : State.DISABLED;
    }

    public State state() {
        return state;
    }

    public enum State {
        PUBLIC,
        DISABLED
    }
}
