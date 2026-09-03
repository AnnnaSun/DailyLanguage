package com.dailylanguage.modelcalljob.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModelCallJobExecutionPropertiesTests {

    @Test
    void acceptsPositiveExecutorSettings() {
        ModelCallJobExecutionProperties.ExecutorSettings executor =
                new ModelCallJobExecutionProperties.ExecutorSettings(2, 8);

        ModelCallJobExecutionProperties properties = new ModelCallJobExecutionProperties(executor);

        assertThat(properties.executor()).isSameAs(executor);
        assertThat(properties.executor().workers()).isEqualTo(2);
        assertThat(properties.executor().queueCapacity()).isEqualTo(8);
    }

    @Test
    void rejectsMissingExecutorSettings() {
        assertThatThrownBy(() -> new ModelCallJobExecutionProperties(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("executor must not be null");
    }

    @Test
    void rejectsNonPositiveWorkerCount() {
        assertThatThrownBy(() -> new ModelCallJobExecutionProperties.ExecutorSettings(0, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("executor workers must be positive");
    }

    @Test
    void rejectsNonPositiveQueueCapacity() {
        assertThatThrownBy(() -> new ModelCallJobExecutionProperties.ExecutorSettings(2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("executor queueCapacity must be positive");
    }
}
