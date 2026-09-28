package com.quizopia.identity.application.activation;

import org.hibernate.exception.ConstraintViolationException;

public final class VerifiedEmailOwnershipConstraint {
    private static final String UNIQUE_INDEX = "uk_user_account_verified_email";

    private VerifiedEmailOwnershipConstraint() {}

    public static boolean isViolation(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && UNIQUE_INDEX.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
