package com.quizopia.identity.application.registration;

import java.util.Objects;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class LocalRegistrationService {
    private static final String USERNAME_UNIQUE_INDEX = "uk_user_account_username";

    private final LocalRegistrationTransaction transaction;

    public LocalRegistrationService(LocalRegistrationTransaction transaction) {
        this.transaction = transaction;
    }

    public LocalRegistrationResult register(LocalRegistrationInput input) {
        Objects.requireNonNull(input, "input");
        try {
            return transaction.register(input);
        } catch (DataIntegrityViolationException exception) {
            if (isUsernameConflict(exception)) {
                return LocalRegistrationResult.usernameConflict();
            }
            throw exception;
        }
    }

    private static boolean isUsernameConflict(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && USERNAME_UNIQUE_INDEX.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
