package com.dailylanguage.security;

import java.util.Objects;
import java.util.UUID;

public record UserContext(UUID userId) {

    public UserContext {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
