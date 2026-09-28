package com.quizopia.identity.application.registration;

import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.Objects;

public final class LocalRegistrationInput {
    private static final int MAX_USERNAME_LENGTH = 255;
    private static final int MAX_EMAIL_LENGTH = 320;

    private final String username;
    private final String email;
    private final RawLocalPassword rawPassword;

    public LocalRegistrationInput(String username, String email, RawLocalPassword rawPassword) {
        this.username = validateRequiredText(username, "username", MAX_USERNAME_LENGTH);
        this.email = validateGmailAddress(validateRequiredText(email, "email", MAX_EMAIL_LENGTH));
        this.rawPassword = Objects.requireNonNull(rawPassword, "rawPassword");
    }

    public String username() {
        return username;
    }

    public String email() {
        return email;
    }

    public RawLocalPassword rawPassword() {
        return rawPassword;
    }

    @Override
    public String toString() {
        return "LocalRegistrationInput{usernamePresent=true, emailPresent=true, rawPasswordPresent=true}";
    }

    private static String validateRequiredText(String value, String fieldName, int maxLength) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    private static String validateGmailAddress(String email) {
        int domainSeparator = email.indexOf('@');
        if (domainSeparator <= 0
                || domainSeparator != email.lastIndexOf('@')
                || !email.substring(domainSeparator + 1).equals("gmail.com")
                || email.substring(0, domainSeparator)
                        .chars()
                        .anyMatch(
                                character -> Character.isWhitespace(character) || Character.isISOControl(character))) {
            throw new IllegalArgumentException("email must use the exact gmail.com domain");
        }
        return email;
    }
}
