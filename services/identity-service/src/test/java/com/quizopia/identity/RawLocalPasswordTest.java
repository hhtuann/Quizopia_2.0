package com.quizopia.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.quizopia.identity.application.registration.LocalRegistrationInput;
import com.quizopia.identity.security.password.RawLocalPassword;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawLocalPasswordTest {
    @Test
    void requiresNonBlankPasswordAndRedactsItsStringRepresentation() {
        String rawPassword = "Plain-secret-" + UUID.randomUUID();
        RawLocalPassword password = RawLocalPassword.from(rawPassword);

        assertEquals(rawPassword, password.value());
        assertFalse(password.toString().contains(rawPassword));
        assertEquals("[REDACTED_LOCAL_PASSWORD]", password.toString());
        assertThrows(NullPointerException.class, () -> RawLocalPassword.from(null));
        assertThrows(IllegalArgumentException.class, () -> RawLocalPassword.from(" \t "));
    }

    @Test
    void registrationInputValidatesTechnicalBoundsWithoutChangingValues() {
        String username = " exact Username ";
        String email = "Exact.Email@example.com";
        RawLocalPassword password = RawLocalPassword.from("Password without policy");

        LocalRegistrationInput input = new LocalRegistrationInput(username, email, password);

        assertEquals(username, input.username());
        assertEquals(email, input.email());
        assertEquals(password, input.rawPassword());
        assertFalse(input.toString().contains("Password without policy"));
        assertThrows(IllegalArgumentException.class, () -> new LocalRegistrationInput(" ", email, password));
        assertThrows(IllegalArgumentException.class, () -> new LocalRegistrationInput(username, "\t", password));
    }
}
