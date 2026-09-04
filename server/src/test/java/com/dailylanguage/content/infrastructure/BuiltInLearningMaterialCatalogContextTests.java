package com.dailylanguage.content.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dailylanguage.content.domain.LearningMaterialCatalog;

/**
 * catalog bean 在启动期 eager 加载 Built-in pack；constructor 校验失败会直接拒绝 context 启动（fail closed）。
 */
class BuiltInLearningMaterialCatalogContextTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(BuiltInLearningMaterialCatalog.class);

    @Test
    void startsApplicationContextWithValidatedBuiltInCatalog() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LearningMaterialCatalog.class);
            assertThat(context).hasSingleBean(BuiltInLearningMaterialCatalog.class);
        });
    }
}
