package com.dailylanguage.identity.security;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.dailylanguage.identity.account.AuthenticatedAccount;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionSerializationTests {

    private final RedisSerializer<Object> serializer = new SessionConfiguration().springSessionDefaultRedisSerializer();

    @Test
    void roundTripsOnlyTheAuthenticatedAccountAsJsonWithoutCredentials() {
        var account = new AuthenticatedAccount(UUID.randomUUID());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));

        byte[] json = serializer.serialize(context);
        SecurityContext restored = (SecurityContext) serializer.deserialize(json);

        assertThat(new String(json, StandardCharsets.UTF_8)).startsWith("{").contains("AuthenticatedAccount");
        assertThat(restored.getAuthentication().getPrincipal()).isEqualTo(account);
        assertThat(restored.getAuthentication().getCredentials()).isNull();
        assertThat(restored.getAuthentication().getAuthorities()).isEmpty();
        assertThat(restored.getAuthentication().isAuthenticated()).isTrue();
    }

    @Test
    void refusesToRestoreAnUnapprovedApplicationType() {
        byte[] json = serializer.serialize(new UnapprovedAttribute("synthetic value"));

        assertThatThrownBy(() -> serializer.deserialize(json)).isInstanceOf(SerializationException.class);
    }

    record UnapprovedAttribute(String value) {
    }
}
