package com.quizopia.quiz.security;

import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public final class TeacherAuthoringPrincipalResolver {
    private static final String TEACHER_AUTHORITY = "ROLE_TEACHER";

    public UUID resolve(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !hasAuthority(authentication, QuizopiaTokenClaims.USER_AUTHORITY)
                || !hasAuthority(authentication, TEACHER_AUTHORITY)) {
            throw denied();
        }

        String subject = authentication.getName();
        try {
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equals(subject)) {
                throw denied();
            }
            return userId;
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new AccessDeniedException("Accepted teacher user principal is required", exception);
        }
    }

    private static boolean hasAuthority(Authentication authentication, String requiredAuthority) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> requiredAuthority.equals(authority.getAuthority()));
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Accepted teacher user principal is required");
    }
}
