package com.quizopia.identity.security.client;

import java.util.Objects;

public final class RawServiceClientSecret {
    private final String value;

    public RawServiceClientSecret(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "RawServiceClientSecret{present=true}";
    }
}
