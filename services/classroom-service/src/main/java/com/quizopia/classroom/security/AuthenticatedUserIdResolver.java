package com.quizopia.classroom.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public final class AuthenticatedUserIdResolver {
    public UUID resolve(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> QuizopiaTokenClaims.USER_AUTHORITY.equals(authority.getAuthority()))) {
            throw new AccessDeniedException("Accepted user principal is required");
        }

        String subject = authentication.getName();
        try {
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equals(subject)) {
                throw new AccessDeniedException("Accepted user principal is required");
            }
            return userId;
        } catch (IllegalArgumentException exception) {
            throw new AccessDeniedException("Accepted user principal is required", exception);
        }
    }
}
