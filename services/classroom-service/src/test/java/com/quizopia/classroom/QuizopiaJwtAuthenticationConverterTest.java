package com.quizopia.classroom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import com.quizopia.classroom.security.QuizopiaJwtAuthenticationConverter;
import com.quizopia.classroom.security.QuizopiaTokenClaims;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class QuizopiaJwtAuthenticationConverterTest {
    private final QuizopiaJwtAuthenticationConverter converter = new QuizopiaJwtAuthenticationConverter();

    @Test
    void userRolesMapToExactRoleAuthoritiesAndUserMarker() {
        UUID userId = UUID.randomUUID();
        AbstractAuthenticationToken authentication = converter.convert(jwt(
                userId.toString(),
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.USER,
                        QuizopiaTokenClaims.ROLES,
                        List.of("STUDENT", "TEACHER", "ADMIN"))));

        assertEquals(userId.toString(), authentication.getName());
        assertEquals(Set.of("TOKEN_USER", "ROLE_STUDENT", "ROLE_TEACHER", "ROLE_ADMIN"), authorities(authentication));
    }

    @Test
    void malformedAndNoncanonicalUserSubjectsAreRejected() {
        assertInvalidUserSubject("not-a-uuid");
        assertInvalidUserSubject("1-1-1-1-1");
        assertInvalidUserSubject(UUID.randomUUID().toString().toUpperCase());
        assertInvalidUserSubject(" ");
    }

    @Test
    void invalidUserRoleClaimTypesAndValuesAreRejected() {
        assertInvalidUserRoles("TEACHER");
        assertInvalidUserRoles(List.of("OWNER"));
        assertInvalidUserRoles(List.of(42));
        assertInvalidUserRoles(Arrays.asList("TEACHER", null));
    }

    @Test
    void userScopeAndMissingPrincipalDiscriminatorAreRejected() {
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        UUID.randomUUID().toString(),
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER"),
                                QuizopiaTokenClaims.SCOPE,
                                List.of("classroom.read")))));
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(UUID.randomUUID().toString(), Map.of())));
    }

    @Test
    void serviceScopesMapToExactServiceAuthoritiesWithoutUserAuthority() {
        AbstractAuthenticationToken authentication = converter.convert(jwt(
                "assessment-service",
                Map.of(
                        QuizopiaTokenClaims.PRINCIPAL_TYPE,
                        QuizopiaTokenClaims.SERVICE,
                        QuizopiaTokenClaims.SCOPE,
                        List.of("classroom.membership.read", "classroom.owner.read"))));

        assertEquals(
                Set.of("TOKEN_SERVICE", "SCOPE_classroom.membership.read", "SCOPE_classroom.owner.read"),
                authorities(authentication));
        assertFalse(authorities(authentication).contains(QuizopiaTokenClaims.USER_AUTHORITY));
    }

    @Test
    void rolesOnServiceAndMalformedServiceScopesAreRejected() {
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        "assessment-service",
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.SERVICE,
                                QuizopiaTokenClaims.SCOPE,
                                List.of("classroom.read"),
                                QuizopiaTokenClaims.ROLES,
                                List.of("TEACHER")))));
        assertInvalidServiceScope(null);
        assertInvalidServiceScope(List.of());
        assertInvalidServiceScope("classroom.read");
        assertInvalidServiceScope(List.of(42));
        assertInvalidServiceScope(Arrays.asList("classroom.read", null));
        assertInvalidServiceScope(List.of(" "));
    }

    private void assertInvalidUserSubject(String subject) {
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(
                        subject,
                        Map.of(
                                QuizopiaTokenClaims.PRINCIPAL_TYPE,
                                QuizopiaTokenClaims.USER,
                                QuizopiaTokenClaims.ROLES,
                                List.of("STUDENT")))));
    }

    private void assertInvalidUserRoles(Object roles) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.USER);
        claims.put(QuizopiaTokenClaims.ROLES, roles);
        assertThrowsExactly(
                InvalidBearerTokenException.class,
                () -> converter.convert(jwt(UUID.randomUUID().toString(), claims)));
    }

    private void assertInvalidServiceScope(Object scope) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(QuizopiaTokenClaims.PRINCIPAL_TYPE, QuizopiaTokenClaims.SERVICE);
        if (scope != null) {
            claims.put(QuizopiaTokenClaims.SCOPE, scope);
        }
        assertThrowsExactly(
                InvalidBearerTokenException.class, () -> converter.convert(jwt("assessment-service", claims)));
    }

    private static Jwt jwt(String subject, Map<String, Object> additionalClaims) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.putAll(additionalClaims);
        return Jwt.withTokenValue("redacted-test-token")
                .header("alg", "RS256")
                .claims(values -> values.putAll(claims))
                .issuedAt(Instant.parse("2026-09-13T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-13T00:05:00Z"))
                .build();
    }

    private static Set<String> authorities(AbstractAuthenticationToken authentication) {
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
    }
}
