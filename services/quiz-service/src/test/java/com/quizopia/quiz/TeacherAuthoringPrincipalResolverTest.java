package com.quizopia.quiz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import com.quizopia.quiz.security.TeacherAuthoringPrincipalResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class TeacherAuthoringPrincipalResolverTest {
    private final TeacherAuthoringPrincipalResolver resolver = new TeacherAuthoringPrincipalResolver();

    @Test
    void teacherUserResolvesCanonicalCallerUuid() {
        UUID userId = UUID.randomUUID();

        assertEquals(userId, resolver.resolve(authentication(userId.toString(), "TOKEN_USER", "ROLE_TEACHER")));
    }

    @Test
    void studentAndTeacherUserResolvesCanonicalCallerUuid() {
        UUID userId = UUID.randomUUID();

        assertEquals(
                userId,
                resolver.resolve(authentication(userId.toString(), "TOKEN_USER", "ROLE_STUDENT", "ROLE_TEACHER")));
    }

    @Test
    void adminAndTeacherUserResolvesCanonicalCallerUuid() {
        UUID userId = UUID.randomUUID();

        assertEquals(
                userId,
                resolver.resolve(authentication(userId.toString(), "TOKEN_USER", "ROLE_ADMIN", "ROLE_TEACHER")));
    }

    @Test
    void studentOnlyUserIsDenied() {
        assertDenied(authentication(UUID.randomUUID().toString(), "TOKEN_USER", "ROLE_STUDENT"));
    }

    @Test
    void adminOnlyUserIsDeniedWithoutImplicitTeacherAuthority() {
        assertDenied(authentication(UUID.randomUUID().toString(), "TOKEN_USER", "ROLE_ADMIN"));
    }

    @Test
    void serviceTokenIsDeniedEvenWhenScopeNameResemblesTeacherPermission() {
        assertDenied(authentication(
                "assessment-service", "TOKEN_SERVICE", "SCOPE_ROLE_TEACHER", "SCOPE_quiz.teacher.write"));
    }

    @Test
    void teacherAuthorityWithoutUserTokenMarkerIsDenied() {
        assertDenied(authentication(UUID.randomUUID().toString(), "ROLE_TEACHER"));
    }

    @Test
    void userTokenMarkerWithoutTeacherAuthorityIsDenied() {
        assertDenied(authentication(UUID.randomUUID().toString(), "TOKEN_USER"));
    }

    @Test
    void malformedAndNoncanonicalUserIdsCannotBecomeAuthoringCallerIds() {
        assertDenied(authentication("not-a-uuid", "TOKEN_USER", "ROLE_TEACHER"));
        assertDenied(authentication("1-1-1-1-1", "TOKEN_USER", "ROLE_TEACHER"));
        assertDenied(authentication(UUID.randomUUID().toString().toUpperCase(), "TOKEN_USER", "ROLE_TEACHER"));
        assertDenied(authentication(" ", "TOKEN_USER", "ROLE_TEACHER"));
    }

    @Test
    void nullAndUnauthenticatedPrincipalsAreDenied() {
        assertDenied(null);
        TestingAuthenticationToken unauthenticated =
                new TestingAuthenticationToken(UUID.randomUUID().toString(), null, "TOKEN_USER", "ROLE_TEACHER");
        unauthenticated.setAuthenticated(false);
        assertDenied(unauthenticated);
    }

    private void assertDenied(Authentication authentication) {
        assertThrowsExactly(AccessDeniedException.class, () -> resolver.resolve(authentication));
    }

    private static Authentication authentication(String subject, String... authorities) {
        return new TestingAuthenticationToken(
                subject,
                null,
                List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList());
    }
}
