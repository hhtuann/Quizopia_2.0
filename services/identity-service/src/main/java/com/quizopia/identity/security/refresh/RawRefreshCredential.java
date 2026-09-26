package com.quizopia.identity.security.refresh;

import java.util.Objects;

/** An opaque refresh credential that is intentionally safe to represent in diagnostics. */
public final class RawRefreshCredential {
    private final String value;

    private RawRefreshCredential(String value) {
        this.value = value;
    }

    public static RawRefreshCredential from(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Refresh credential must not be blank");
        }
        return new RawRefreshCredential(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "[REDACTED_REFRESH_CREDENTIAL]";
    }
}
