package com.dailylanguage.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class MapperSqlSafetyTests {

    @Test
    void mapperXmlNeverUsesRawStringSubstitutionOrPlainStatements() throws IOException {
        Resource[] mapperResources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:/mapper/**/*.xml");

        assertThat(mapperResources).isNotEmpty();
        for (Resource resource : mapperResources) {
            String mapperXml = readResource(resource);

            assertThat(mapperXml)
                    .as(resource.getDescription())
                    .doesNotContain("${")
                    .doesNotMatch("(?s).*statementType\\s*=\\s*['\"]STATEMENT['\"].*");
        }
    }

    @Test
    void allVariableValuesUsePreparedStatementParameters() throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:/mapper/LanguageProfileMapper.xml");
        String mapperXml = readResource(resource);

        assertThat(mapperXml)
                .contains("#{userId, jdbcType=OTHER}")
                .contains("#{languageCode, jdbcType=VARCHAR}")
                .contains("#{languageProfileId, jdbcType=OTHER}");
    }

    private static String readResource(Resource resource) throws IOException {
        assertThat(resource.exists()).as(resource.getDescription()).isTrue();
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
