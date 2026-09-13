package com.quizopia.classroom.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Looks up the internal user ID of a verified Quizopia account.
 *
 * <p>The supplied string is the email entered in the manual-add flow. This port deliberately does
 * not define cross-service canonicalization; a future adapter must follow an explicitly accepted
 * Identity matching contract.
 */
@FunctionalInterface
public interface VerifiedUserLookup {
    Optional<UUID> findVerifiedUserIdByEmail(String suppliedEmail);
}
