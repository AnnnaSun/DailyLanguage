package com.dailylanguage.authentication.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashCapacityConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withBean(PasswordHashConcurrencyGate.class);

    @Test
    void developmentAndTestUseTheSafeDefaultCapacity() {
        contextRunner.run(context -> {
            assertThat(context)
                    .hasNotFailed()
                    .hasSingleBean(PasswordHashConcurrencyGate.class);
            assertThat(context.getEnvironment().getProperty(
                    "app.security.password-hashing.max-concurrent",
                    Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void hostedRequiresExplicitPasswordHashCapacity() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=hosted",
                        "PASSWORD_HASH_MAX_CONCURRENT=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void hostedStartsWithExplicitPasswordHashCapacity() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=hosted",
                        "PASSWORD_HASH_MAX_CONCURRENT=2")
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(PasswordHashConcurrencyGate.class);
                    assertThat(context.getEnvironment().getProperty(
                            "app.security.password-hashing.max-concurrent",
                            Integer.class)).isEqualTo(2);
                });
    }
}
