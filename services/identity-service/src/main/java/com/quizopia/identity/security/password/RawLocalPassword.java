package com.quizopia.identity.security.password;

import java.util.Objects;

/** A transient raw local password that is intentionally safe to represent in diagnostics. */
public final class RawLocalPassword {
    private final String value;

    private RawLocalPassword(String value) {
        this.value = value;
    }

    public static RawLocalPassword from(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Local password must not be blank");
        }
        return new RawLocalPassword(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED_LOCAL_PASSWORD]";
    }
}
