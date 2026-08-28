package com.dailylanguage.authentication.application;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RegistrationCapabilityTests {

    @Test
    void enabledRegistrationIsPublic() {
        RegistrationCapability capability = new RegistrationCapability(true);

        assertThat(capability.state()).isEqualTo(RegistrationCapability.State.PUBLIC);
    }

    @Test
    void disabledRegistrationIsUnavailable() {
        RegistrationCapability capability = new RegistrationCapability(false);

        assertThat(capability.state()).isEqualTo(RegistrationCapability.State.DISABLED);
    }

    @Test
    void nonBooleanConfigurationPreventsApplicationContextStartup() {
        new ApplicationContextRunner()
                .withBean(RegistrationCapability.class)
                .withPropertyValues("app.registration-enabled=invalid")
                .run(context -> assertThat(context).hasFailed());
    }
}
