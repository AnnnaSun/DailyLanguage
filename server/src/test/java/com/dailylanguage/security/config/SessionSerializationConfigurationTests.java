package com.dailylanguage.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dailylanguage.security.domain.UserContext;

class SessionSerializationConfigurationTests {

    private final RedisSerializer<Object> serializer =
            new SessionConfiguration().springSessionDefaultRedisSerializer();

    @Test
    void roundTripsAuthenticatedUserContextAsJson() {
        UUID userId = UUID.randomUUID();
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new UserContext(userId),
                null,
                List.of()));

        byte[] serialized = serializer.serialize(securityContext);
        Object restoredValue = serializer.deserialize(serialized);

        assertThat(new String(serialized, StandardCharsets.UTF_8))
                .contains("UserContext")
                .contains(userId.toString());
        assertThat(serialized[0]).isNotEqualTo((byte) 0xAC);
        assertThat(restoredValue).isInstanceOf(SecurityContext.class);

        SecurityContext restoredContext = (SecurityContext) restoredValue;
        assertThat(restoredContext.getAuthentication().getPrincipal())
                .isEqualTo(new UserContext(userId));
        assertThat(restoredContext.getAuthentication().getCredentials()).isNull();
        assertThat(restoredContext.getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    void rejectsTypeOutsideSecurityAndUserContextAllowlist() {
        byte[] serialized = serializer.serialize(new DisallowedSessionAttribute("unsafe"));

        assertThatThrownBy(() -> serializer.deserialize(serialized))
                .isInstanceOf(SerializationException.class);
    }

    static class DisallowedSessionAttribute {

        private final String value;

        DisallowedSessionAttribute(String value) {
            this.value = value;
        }

        String getValue() {
            return value;
        }
    }
}
