package com.quizopia.identity.application.localauthentication;

import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.Objects;

public final class LocalAuthenticationInput {
    private static final int MAX_IDENTIFIER_LENGTH = 320;

    private final String identifier;
    private final RawLocalPassword rawPassword;

    public LocalAuthenticationInput(String identifier, RawLocalPassword rawPassword) {
        Objects.requireNonNull(identifier, "identifier");
        if (identifier.isBlank()) {
            throw new IllegalArgumentException("identifier must not be blank");
        }
        if (identifier.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("identifier must be at most 320 characters");
        }
        this.identifier = identifier;
        this.rawPassword = Objects.requireNonNull(rawPassword, "rawPassword");
    }

    public String identifier() {
        return identifier;
    }

    public RawLocalPassword rawPassword() {
        return rawPassword;
    }

    @Override
    public String toString() {
        return "LocalAuthenticationInput{identifierPresent=true, rawPasswordPresent=true}";
    }
}
