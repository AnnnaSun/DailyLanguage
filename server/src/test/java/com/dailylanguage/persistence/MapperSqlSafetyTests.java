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
        Resource languageProfileMapper = new PathMatchingResourcePatternResolver()
                .getResource("classpath:/mapper/LanguageProfileMapper.xml");
        Resource localAuthenticationMapper = new PathMatchingResourcePatternResolver()
                .getResource("classpath:/mapper/LocalAuthenticationMapper.xml");
        Resource modelCallJobMapper = new PathMatchingResourcePatternResolver()
                .getResource("classpath:/mapper/ModelCallJobMapper.xml");

        assertThat(readResource(languageProfileMapper))
                .contains("#{userId, jdbcType=OTHER}")
                .contains("#{languageCode, jdbcType=VARCHAR}")
                .contains("#{languageProfileId, jdbcType=OTHER}")
                .contains("ON CONFLICT (user_id, language_code) DO NOTHING");
        assertThat(readResource(localAuthenticationMapper))
                .contains("#{userId, jdbcType=OTHER}")
                .contains("#{provider, jdbcType=VARCHAR}")
                .contains("#{providerSubject, jdbcType=VARCHAR}")
                .contains("#{authenticationIdentityId, jdbcType=OTHER}")
                .contains("#{encodedPasswordHash, jdbcType=VARCHAR}");
        assertThat(readResource(modelCallJobMapper))
                .contains("#{userId, jdbcType=OTHER}")
                .contains("#{languageProfileId, jdbcType=OTHER}")
                .contains("#{providerId, jdbcType=VARCHAR}")
                .contains("#{modelId, jdbcType=VARCHAR}")
                .contains("#{workflowId, jdbcType=OTHER}")
                .contains("#{expectedRowVersion, jdbcType=BIGINT}")
                .contains("#{generatedText, jdbcType=VARCHAR}")
                .contains("#{finishReason, jdbcType=VARCHAR}")
                .contains("#{inputTokens, jdbcType=BIGINT}")
                .contains("#{outputTokens, jdbcType=BIGINT}")
                .contains("#{terminalExecutionStatus, jdbcType=VARCHAR}")
                .contains("#{failureKind, jdbcType=VARCHAR}")
                .contains("#{retryAfterSeconds, jdbcType=BIGINT}")
                .contains("#{expiresAt, jdbcType=TIMESTAMP_WITH_TIMEZONE}")
                .doesNotContain("${");
    }

    private static String readResource(Resource resource) throws IOException {
        assertThat(resource.exists()).as(resource.getDescription()).isTrue();
        try (var input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
